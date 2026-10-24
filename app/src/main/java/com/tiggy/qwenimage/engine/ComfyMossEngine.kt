package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.BitmapFactory
import com.tiggy.qwenimage.data.MOSS_TEXT_ENCODER
import com.tiggy.qwenimage.data.MOSS_VAE
import com.tiggy.qwenimage.data.MossGenerationResult
import com.tiggy.qwenimage.data.MossSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ComfyMossEngine internal constructor(
    private val outputDirectory: File,
    private val imageDimensionsReader: (File) -> Pair<Int, Int>,
    private val pollIntervalMs: Long = 500L,
    private val maxWaitMs: Long = 3_600_000L,
    private val responseLimitBytes: Long = 16L * 1024 * 1024
) : AutoCloseable {
    constructor(context: Context) : this(
        outputDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "outputs"),
        imageDimensionsReader = { readMossImageDimensions(it) }
    )

    private val activeClients = ConcurrentHashMap.newKeySet<WorkstationClient>()

    @Volatile
    private var activeGeneration: ActiveGeneration? = null

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var closed = false

    init {
        require(pollIntervalMs >= 0 && maxWaitMs > 0) { "轮询时间配置无效" }
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) throw IOException("无法创建 MOSS 输出目录")
    }

    suspend fun executeGeneration(
        jobId: String,
        settings: MossSettings,
        onUpdate: (EngineUpdate) -> Unit = {}
    ): MossGenerationResult = withContext(Dispatchers.IO) {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "MOSS 任务 ID 无效" }
        settings.validate()
        val generation = ActiveGeneration(jobId, settings.serverUrl)
        synchronized(this@ComfyMossEngine) {
            require(!closed) { "MOSS 引擎已关闭" }
            check(activeGeneration == null) { "已有 MOSS 任务正在执行" }
            cancelRequested = false
            activeGeneration = generation
        }
        val client = WorkstationClient(settings.serverUrl, responseLimitBytes)
        activeClients += client
        var outputFile: File? = null
        try {
            throwIfCancelled()
            onUpdate(EngineUpdate(null, "正在检查 MOSS 工作站"))
            validateServer(client, settings)
            throwIfCancelled()

            val workflow = MossWorkflow.build(settings, "omni_pocket_moss_$jobId")
            val payload = JSONObject().apply {
                put("prompt", workflow)
                put("client_id", jobId)
            }
            onUpdate(EngineUpdate(null, "正在提交 MOSS 任务"))
            val response = client.postJson("/prompt", payload, 30_000)
            promptSubmissionError(response)?.let { throw IOException(it) }
            val promptId = response.optString("prompt_id")
            if (promptId.isBlank() || promptId.length > 256) throw IOException("ComfyUI 未返回有效 prompt_id")
            generation.promptId = promptId
            throwIfCancelled()

            val reference = awaitImage(client, promptId, onUpdate)
            throwIfCancelled()
            val extension = reference.filename.substringAfterLast('.', "png").lowercase()
                .takeIf { it in IMAGE_EXTENSIONS } ?: "png"
            val target = File(outputDirectory, "$jobId.$extension")
            outputFile = target
            onUpdate(EngineUpdate(null, "正在接收图像"))
            client.download(reference.toViewPath(), target, 128L * 1024 * 1024, "image")
            throwIfCancelled()

            val dimensions = imageDimensionsReader(target)
            require(dimensions.first > 0 && dimensions.second > 0) { "下载内容不是可识别的图片" }
            throwIfCancelled()
            val result = MossGenerationResult(target, dimensions.first, dimensions.second, promptId)
            onUpdate(EngineUpdate(1f, "图像已保存", target.name))
            result
        } catch (error: Throwable) {
            outputFile?.delete()
            if (error is CancellationException || cancelRequested) {
                requestRemoteCancellation(generation)
                if (cancelRequested) throw CancellationException("已取消 MOSS 图像生成") else throw error
            }
            throw error
        } finally {
            activeClients -= client
            client.close()
            synchronized(this@ComfyMossEngine) {
                if (activeGeneration === generation) activeGeneration = null
            }
        }
    }

    fun cancelGeneration() {
        cancelRequested = true
        activeClients.forEach { it.close() }
        activeGeneration?.let { requestRemoteCancellation(it) }
    }

    override fun close() {
        closed = true
        cancelGeneration()
    }

    private fun validateServer(client: WorkstationClient, settings: MossSettings) {
        val objectInfo = client.getJson("/object_info", 20_000)
        val missing = MossWorkflow.requiredNodeTypes(settings).filterNot { objectInfo.has(it) }
        if (missing.isNotEmpty()) {
            throw IOException("ComfyUI 0.42+ 缺少 MOSS 必需节点：${missing.joinToString()}")
        }
        val unetOptions = comboOptions(objectInfo, "UNETLoader", "unet_name")
        val clipOptions = comboOptions(objectInfo, "CLIPLoader", "clip_name")
        val clipTypes = comboOptions(objectInfo, "CLIPLoader", "type")
        val vaeOptions = comboOptions(objectInfo, "VAELoader", "vae_name")
        if ("moss_image" !in clipTypes) throw IOException("CLIPLoader 不支持 moss_image，请升级 ComfyUI 0.42+")
        if (settings.checkpoint.fileName !in unetOptions) {
            throw IOException("工作站缺少所选 MOSS checkpoint：${settings.checkpoint.fileName}")
        }
        if (MOSS_TEXT_ENCODER !in clipOptions) throw IOException("工作站缺少 MOSS 文本编码器：$MOSS_TEXT_ENCODER")
        if (MOSS_VAE !in vaeOptions) throw IOException("工作站缺少 MOSS VAE：$MOSS_VAE")
    }

    private suspend fun awaitImage(
        client: WorkstationClient,
        promptId: String,
        onUpdate: (EngineUpdate) -> Unit
    ): MossImageReference {
        val startedAt = System.nanoTime()
        var lastStage: String? = null
        while (true) {
            throwIfCancelled()
            if ((System.nanoTime() - startedAt) / 1_000_000L > maxWaitMs) {
                throw IOException("等待 ComfyUI 生成结果超时")
            }
            val history = client.getJson("/history/${promptId.pathSegment()}")
            val entry = history.optJSONObject(promptId)
            if (entry != null) {
                historyError(entry)?.let { throw IOException(it) }
                val images = outputImages(entry)
                if (images.isNotEmpty()) {
                    if (images.size != 1) throw IOException("MOSS SaveImage 返回了异常数量的图片：${images.size}")
                    return images.single()
                }
                val status = entry.optJSONObject("status")
                if (status?.optBoolean("completed", false) == true ||
                    status?.optString("status_str").equals("success", ignoreCase = true)
                ) {
                    throw IOException("ComfyUI 已完成任务，但 SaveImage 没有返回图片")
                }
            }

            val queue = client.getJson("/queue")
            val running = queuePromptIds(queue, "running")
            val pending = queuePromptIds(queue, "pending")
            val queuePosition = pending.indexOf(promptId)
            val stage = when {
                promptId in running -> "工作站正在生成"
                queuePosition >= 0 -> "工作站排队中 · 第 ${queuePosition + 1} 位"
                else -> "等待工作站确认任务"
            }
            if (stage != lastStage) {
                onUpdate(EngineUpdate(null, stage, "prompt_id=$promptId"))
                lastStage = stage
            }
            delay(pollIntervalMs)
        }
    }

    private suspend fun throwIfCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelRequested) throw CancellationException("已取消 MOSS 图像生成")
    }

    private fun requestRemoteCancellation(generation: ActiveGeneration) {
        val promptId = generation.promptId ?: return
        if (!generation.remoteCancellationStarted.compareAndSet(false, true)) return
        Thread({
            runCatching { cancelComfyPrompt(generation.serverUrl, promptId) }
        }, "moss-cancel-${generation.jobId}").apply {
            isDaemon = true
            start()
        }
    }

    private class ActiveGeneration(val jobId: String, val serverUrl: String) {
        @Volatile
        var promptId: String? = null
        val remoteCancellationStarted = AtomicBoolean(false)
    }

    internal data class MossImageReference(val filename: String, val subfolder: String, val type: String) {
        fun toViewPath(): String = buildString {
            append("/view?filename=")
            append(filename.queryValue())
            append("&subfolder=")
            append(subfolder.queryValue())
            append("&type=")
            append(type.queryValue())
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

internal fun cancelComfyPrompt(serverUrl: String, promptId: String) {
    require(promptId.isNotBlank()) { "prompt_id 不能为空" }
    WorkstationClient(serverUrl).use { client ->
        val queue = client.getJson("/queue", 5_000)
        if (promptId in queuePromptIds(queue, "running")) {
            client.postText("/interrupt", readTimeoutMs = 5_000)
        } else if (promptId in queuePromptIds(queue, "pending")) {
            val payload = JSONObject().put("delete", JSONArray().put(promptId))
            client.postText("/queue", payload.toString(), 5_000)
        }
    }
}

private fun comboOptions(objectInfo: JSONObject, nodeType: String, inputName: String): Set<String> {
    val specification = objectInfo.optJSONObject(nodeType)
        ?.optJSONObject("input")
        ?.optJSONObject("required")
        ?.opt(inputName) as? JSONArray ?: return emptySet()
    val options = specification.optJSONArray(0) ?: return emptySet()
    return (0 until options.length()).map { options.optString(it) }.filterTo(mutableSetOf()) { it.isNotBlank() }
}

private fun promptSubmissionError(response: JSONObject): String? {
    val error = response.opt("error") ?: return null
    if (error == JSONObject.NULL) return null
    val detail = when (error) {
        is JSONObject -> error.optString("message").ifBlank { error.toString() }
        else -> error.toString()
    }
    val nodeErrors = response.optJSONObject("node_errors")?.toString().orEmpty()
    return "ComfyUI 拒绝 MOSS 工作流：$detail${if (nodeErrors.isBlank()) "" else " · $nodeErrors"}"
}

private fun historyError(entry: JSONObject): String? {
    val status = entry.optJSONObject("status") ?: return null
    val messages = status.optJSONArray("messages")
    if (messages != null) {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONArray(index) ?: continue
            val type = message.optString(0)
            if (type == "execution_error" || type == "execution_interrupted") {
                val detail = message.optJSONObject(1)?.optString("exception_message").orEmpty()
                return "ComfyUI 执行 MOSS 失败：${detail.ifBlank { type }}"
            }
        }
    }
    val state = status.optString("status_str")
    return if (state.equals("error", ignoreCase = true) || state.equals("interrupted", ignoreCase = true)) {
        "ComfyUI 执行 MOSS 失败：$state"
    } else {
        null
    }
}

private fun outputImages(entry: JSONObject): List<ComfyMossEngine.MossImageReference> {
    val outputs = entry.optJSONObject("outputs") ?: return emptyList()
    val images = mutableListOf<ComfyMossEngine.MossImageReference>()
    val nodeIds = outputs.keys()
    while (nodeIds.hasNext()) {
        val nodeOutput = outputs.optJSONObject(nodeIds.next()) ?: continue
        val array = nodeOutput.optJSONArray("images") ?: continue
        for (index in 0 until array.length()) {
            val image = array.optJSONObject(index) ?: continue
            val filename = image.optString("filename")
            val type = image.optString("type")
            if (filename.isBlank() || type.isBlank()) throw IOException("ComfyUI SaveImage 图片信息不完整")
            images += ComfyMossEngine.MossImageReference(filename, image.optString("subfolder"), type)
        }
    }
    return images
}

private fun queuePromptIds(queue: JSONObject, key: String): List<String> {
    val entries = queue.optJSONArray("queue_$key") ?: return emptyList()
    return (0 until entries.length()).mapNotNull { index ->
        entries.optJSONArray(index)?.optString(1)?.takeIf { it.isNotBlank() }
    }
}

private fun String.pathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

private fun String.queryValue(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun readMossImageDimensions(file: File): Pair<Int, Int> {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: throw IOException("下载内容不是可识别的图片")
    return try {
        bitmap.width to bitmap.height
    } finally {
        bitmap.recycle()
    }
}
