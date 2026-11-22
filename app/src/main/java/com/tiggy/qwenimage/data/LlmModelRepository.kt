package com.tiggy.qwenimage.data

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class LlmModelRepository(
    private val context: Context,
    private val hubPreferences: HubPreferences = HubPreferences.get(context)
) {
    val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/llm")
    val catalog: List<LlmModelSpec> by lazy {
        context.assets.open("llm_models.json").use { input ->
            LlmCatalog.parse(readLimited(input, MAX_MANIFEST_BYTES))
        }
    }

    fun model(id: String): LlmModelSpec = catalog.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("未知 LLM 模型：$id")

    fun directory(id: String): File {
        model(id)
        return File(root, id)
    }

    fun status(id: String): LlmModelStatus {
        val spec = model(id)
        val directory = directory(id)
        recoverBackup(spec)
        val manifest = readManifest(spec)
        val present = spec.artifacts.count { File(directory, it.path).let { file -> file.isFile && file.length() == it.size } }
        val verified = manifest != null && verificationValid(spec, directory)
        val message = when {
            verified -> "本机可用"
            present == spec.artifacts.size -> "需要校验"
            present > 0 -> "$present/${spec.artifacts.size} 个文件"
            else -> "未下载"
        }
        return LlmModelStatus(spec, directory, present, verified, message)
    }

    suspend fun download(id: String, onProgress: (LlmDownloadProgress) -> Unit) = withContext(Dispatchers.IO) {
        val spec = model(id)
        if (spec.kind == LlmModelKind.Decision) {
            downloadDecision(spec, onProgress)
            return@withContext
        }
        recoverBackup(spec)
        val staging = File(root, ".${spec.id}.downloading")
        check(staging.isDirectory || staging.mkdirs()) { "无法创建模型暂存目录" }
        val operation = coroutineContext
        val hubSettings = hubPreferences.current()
        val remaining = spec.artifacts.sumOf { artifact ->
            val target = File(staging, artifact.path)
            val part = File(target.path + ".part")
            val identity = File(target.path + ".resume")
            when {
                matches(target, artifact, operation) -> 0L
                part.isFile && part.length() <= artifact.size &&
                    runCatching { identity.readText() }.getOrNull() == artifact.sha256 -> artifact.size - part.length()
                else -> artifact.size
            }
        }
        val available = StatFs(staging.path).availableBytes
        check(available >= remaining + RESERVE_BYTES) { "空间不足，请预留下载空间和 512MB 余量" }
        val total = spec.totalBytes
        var completed = 0L
        for (artifact in spec.artifacts) {
            operation.ensureActive()
            onProgress(LlmDownloadProgress(spec.id, artifact.path, completed, total, false))
            val target = File(staging, artifact.path)
            if (!matches(target, artifact, operation)) {
                downloadArtifact(artifact.url, artifact, target, hubSettings, operation) { done ->
                    onProgress(LlmDownloadProgress(spec.id, artifact.path, completed + done, total, false))
                }
            }
            completed += artifact.size
            onProgress(LlmDownloadProgress(spec.id, artifact.path, completed, total, false))
        }
        val manifest = File(staging, "manifest.json")
        val temporary = File(staging, "manifest.json.tmp")
        temporary.writeText(LlmCatalog.manifest(spec))
        check(temporary.renameTo(manifest)) { "无法保存模型清单" }
        verifyDirectory(staging, spec, operation)
        activate(staging, spec)
        onProgress(LlmDownloadProgress(spec.id, "校验完成", total, total, true))
    }

    /**
     * Downloads Elephant-1 9B directly on-device: copies the 4 deterministic small metadata JSONs
     * bundled in assets/elephant-1/ and fetches the pinned Q4_K_M GGUF via the configured Hub mirror.
     */
    private suspend fun downloadDecision(spec: LlmModelSpec, onProgress: (LlmDownloadProgress) -> Unit) {
        recoverBackup(spec)
        val staging = File(root, ".${spec.id}.downloading")
        check(staging.isDirectory || staging.mkdirs()) { "无法创建决策模型暂存目录" }
        val operation = coroutineContext
        val hubSettings = hubPreferences.current()
        val weightsArtifact = spec.artifacts.single { it.path == LlmModelSpec.DECISION_WEIGHTS }
        val weightsTarget = File(staging, weightsArtifact.path)
        val part = File(weightsTarget.path + ".part")
        val identity = File(weightsTarget.path + ".resume")
        val remaining = when {
            matches(weightsTarget, weightsArtifact, operation) -> 0L
            part.isFile && part.length() <= weightsArtifact.size &&
                runCatching { identity.readText() }.getOrNull() == weightsArtifact.sha256 -> weightsArtifact.size - part.length()
            else -> weightsArtifact.size
        }
        val available = StatFs(staging.path).availableBytes
        check(available >= remaining + RESERVE_BYTES) { "空间不足，请至少预留下载空间和 512MB 余量" }

        val total = spec.totalBytes
        var completed = 0L
        for (artifact in spec.artifacts) {
            operation.ensureActive()
            val target = File(staging, artifact.path)
            if (artifact.path == LlmModelSpec.DECISION_WEIGHTS) {
                onProgress(LlmDownloadProgress(spec.id, artifact.path, completed, total, false))
                if (!matches(target, artifact, operation)) {
                    downloadArtifact(ELEPHANT_GGUF_URL, artifact, target, hubSettings, operation) { done ->
                        onProgress(LlmDownloadProgress(spec.id, artifact.path, completed + done, total, false))
                    }
                }
            } else {
                context.assets.open("elephant-1/${artifact.path}").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                check(matches(target, artifact, operation)) { "内置决策元数据 ${artifact.path} 校验失败" }
            }
            completed += artifact.size
            onProgress(LlmDownloadProgress(spec.id, artifact.path, completed, total, false))
        }
        val manifest = File(staging, "manifest.json")
        val temporary = File(staging, "manifest.json.tmp")
        temporary.writeText(LlmCatalog.manifest(spec))
        check(temporary.renameTo(manifest)) { "无法保存模型清单" }
        verifyDirectory(staging, spec, operation)
        activate(staging, spec)
        onProgress(LlmDownloadProgress(spec.id, "校验完成", total, total, true))
    }

    suspend fun verify(id: String, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val spec = model(id)
        recoverBackup(spec)
        readManifest(spec) ?: error("目录缺少有效的固定版本 manifest.json")
        onProgress("正在校验 ${spec.name}")
        verifyDirectory(directory(id), spec, coroutineContext)
    }

    fun delete(id: String) {
        val directory = directory(id)
        check(!File(directory, ".verified.json.tmp").exists()) { "模型校验尚未结束" }
        if (directory.exists()) check(directory.deleteRecursively()) { "无法删除模型" }
        File(root, ".${model(id).id}.downloading").deleteRecursively()
    }

    private fun downloadArtifact(
        url: String,
        artifact: LlmModelArtifact,
        target: File,
        hubSettings: HubSettings,
        operation: CoroutineContext,
        progress: (Long) -> Unit
    ) {
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建模型目录" }
        val part = File(target.path + ".part")
        val identity = File(target.path + ".resume")
        if (part.length() > artifact.size || (part.isFile && runCatching { identity.readText() }.getOrNull() != artifact.sha256)) {
            check(part.delete()) { "无法清理失效的续传文件" }
        }
        identity.writeText(artifact.sha256)
        LlmHttp.download(url, part, artifact.size, operation, hubSettings, progress)
        if (!matches(part, artifact, operation)) {
            part.delete()
            identity.delete()
            error("${artifact.path} 大小或 SHA-256 不匹配")
        }
        if (target.exists()) check(target.delete()) { "无法替换旧模型文件" }
        check(part.renameTo(target)) { "无法保存 ${artifact.path}" }
        identity.delete()
    }

    private fun readManifest(spec: LlmModelSpec): LlmModelSpec? = runCatching {
        val text = File(directory(spec.id), "manifest.json").inputStream().use { readLimited(it, MAX_MANIFEST_BYTES) }
        val restored = LlmCatalog.parse(text).single()
        require(restored.id == spec.id && restored.toJson().toString() == spec.toJson().toString()) {
            "模型清单与内置固定版本不一致"
        }
        restored
    }.getOrNull()

    private fun verificationValid(spec: LlmModelSpec, directory: File): Boolean = runCatching {
        val verification = JSONObject(File(directory, ".verified.json").readText())
        verification.optString("manifestHash") == sha256(File(directory, "manifest.json")) &&
            spec.artifacts.all { artifact ->
                val file = File(directory, artifact.path)
                val record = verification.optJSONObject(artifact.path)
                file.isFile && record != null && record.optLong("size") == file.length() &&
                    record.optLong("modified") == file.lastModified() && record.optString("sha256") == artifact.sha256
            }
    }.getOrDefault(false)

    private fun verifyDirectory(directory: File, spec: LlmModelSpec, operation: CoroutineContext) {
        spec.artifacts.forEach { artifact ->
            operation.ensureActive()
            check(matches(File(directory, artifact.path), artifact, operation)) { "${artifact.path} 校验失败" }
        }
        val verification = JSONObject().put("manifestHash", sha256(File(directory, "manifest.json")))
        spec.artifacts.forEach { artifact ->
            val file = File(directory, artifact.path)
            verification.put(artifact.path, JSONObject().put("size", file.length())
                .put("modified", file.lastModified()).put("sha256", artifact.sha256))
        }
        val temporary = File(directory, ".verified.json.tmp")
        temporary.writeText(verification.toString())
        check(temporary.renameTo(File(directory, ".verified.json"))) { "无法保存校验记录" }
    }

    private fun activate(staging: File, spec: LlmModelSpec) {
        val target = directory(spec.id)
        val backup = File(root, ".${spec.id}.backup")
        if (backup.exists()) check(backup.deleteRecursively()) { "无法清理旧备份" }
        if (target.exists()) check(target.renameTo(backup)) { "无法备份现有模型" }
        if (!staging.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("无法启用模型，已恢复原有文件")
        }
        backup.deleteRecursively()
    }

    private fun recoverBackup(spec: LlmModelSpec) {
        val backup = File(root, ".${spec.id}.backup")
        if (!directory(spec.id).exists() && backup.exists()) backup.renameTo(directory(spec.id))
    }

    private companion object {
        const val RESERVE_BYTES = 512L * 1024 * 1024
        const val ELEPHANT_GGUF_URL =
            "https://huggingface.co/prithivMLmods/elephant-1-GGUF/resolve/77d938a13309d7b3da168d87abf5589f9329b122/elephant-1.Q4_K_M.gguf"
    }
}

data class LlmDownloadProgress(
    val modelId: String,
    val file: String,
    val completed: Long,
    val total: Long,
    val verified: Boolean
)

internal object LlmHttp {
    fun download(
        url: String,
        part: File,
        size: Long,
        operation: CoroutineContext,
        hubSettings: HubSettings = HubSettings(mode = HubMirrorMode.Official),
        progress: (Long) -> Unit
    ) {
        ResumableHttpDownloader.download(
            url = url,
            part = part,
            expectedSize = size,
            operation = operation,
            hubSettings = hubSettings,
            maxAttempts = if (url.startsWith("http://127.0.0.1")) 1 else 3
        ) { done, _ -> progress(done) }
    }
}

private fun readLimited(input: InputStream, maximum: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var length = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        length += count
        require(length <= maximum) { "模型清单过大" }
        if (count > 0) output.write(buffer, 0, count)
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}

private fun matches(file: File, artifact: LlmModelArtifact, operation: CoroutineContext): Boolean =
    file.isFile && file.length() == artifact.size && sha256(file, operation) == artifact.sha256

private fun sha256(file: File, operation: CoroutineContext? = null): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            operation?.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val MAX_MANIFEST_BYTES = 256 * 1024
