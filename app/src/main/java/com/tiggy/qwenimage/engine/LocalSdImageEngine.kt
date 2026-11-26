package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.tiggy.qwenimage.data.InstalledHubModel
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.MossGenerationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

data class CustomSdSettings(
    val modelId: String = "",
    val prompt: String = "",
    val negativePrompt: String = "lowres, bad anatomy, blurry, watermark, text",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Double = 7.0,
    val seed: Long = 42L,
    val useOpenCl: Boolean = false,
    val sampler: String = "euler",
    val vaeTiling: Boolean = true,
    val inputImageUri: Uri? = null,
    val strength: Double = 0.75
) {
    fun validate() {
        require(modelId.isNotBlank()) { "请先选择已下载的图像模型" }
        require(prompt.isNotBlank()) { "请输入提示词" }
        require(width in 256..1024 && height in 256..1024 && width % 64 == 0 && height % 64 == 0) {
            "宽高必须为 256–1024 内的 64 倍数"
        }
        require(steps in 1..60) { "步数必须为 1–60" }
        require(cfg.isFinite() && cfg in 0.0..20.0) { "CFG 必须在 0 到 20 之间" }
        require(strength.isFinite() && strength in 0.05..1.0) { "重绘强度必须在 0.05 到 1.0 之间" }
        require(seed >= 0L) { "Seed 不能为负数" }
        require(sampler in SUPPORTED_SD_SAMPLERS) { "不支持的采样器：$sampler" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("modelId", modelId)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("width", width)
        put("height", height)
        put("steps", steps)
        put("cfg", cfg)
        put("seed", seed)
        put("useOpenCl", useOpenCl)
        put("sampler", sampler)
        put("vaeTiling", vaeTiling)
        put("strength", strength)
    }

    companion object {
        fun fromJson(o: JSONObject?): CustomSdSettings = CustomSdSettings(
            modelId = o?.optString("modelId", "") ?: "",
            prompt = o?.optString("prompt", "") ?: "",
            negativePrompt = o?.optString("negativePrompt", "lowres, bad anatomy, blurry, watermark, text")
                ?: "lowres, bad anatomy, blurry, watermark, text",
            width = o?.optInt("width", 512) ?: 512,
            height = o?.optInt("height", 512) ?: 512,
            steps = (o?.optInt("steps", 20) ?: 20).coerceIn(1, 60),
            cfg = (o?.optDouble("cfg", 7.0) ?: 7.0).coerceIn(0.0, 20.0),
            seed = (o?.optLong("seed", 42L) ?: 42L).coerceAtLeast(0L),
            useOpenCl = o?.optBoolean("useOpenCl", false) ?: false,
            sampler = o?.optString("sampler", "euler")?.takeIf { it in SUPPORTED_SD_SAMPLERS } ?: "euler",
            vaeTiling = o?.optBoolean("vaeTiling", true) ?: true,
            strength = (o?.optDouble("strength", 0.75) ?: 0.75).coerceIn(0.05, 1.0)
        )

        fun defaultsFor(model: InstalledHubModel, current: CustomSdSettings): CustomSdSettings {
            val lower = (model.repo + " " + model.entryFile).lowercase(Locale.ROOT)
            val isTurbo = lower.contains("turbo") || lower.contains("lightning") || lower.contains("lcm")
            val metaSteps = model.metadata["defaultSteps"]?.toIntOrNull()?.coerceIn(1, 60)
            val metaCfg = model.metadata["defaultCfg"]?.toDoubleOrNull()?.coerceIn(0.0, 20.0)
            val defaultSampler = if (lower.contains("lcm")) "lcm" else "euler"
            return current.copy(
                modelId = model.id,
                steps = metaSteps ?: if (isTurbo) 4 else 20,
                cfg = metaCfg ?: if (isTurbo) 1.0 else 7.0,
                sampler = defaultSampler
            )
        }
    }
}

/**
 * Executes single-file (SD 1.5 / SDXL / Turbo) and modular (Z-Image-Turbo / FLUX.2-Klein /
 * FLUX.3-Klein / ERNIE-Image-Turbo) diffusion checkpoints downloaded from Hugging Face / hf-mirror
 * via libsd_jni.so inside the isolated `:engine` worker service.
 */
class LocalSdImageEngine(
    private val context: Context,
    private val hubModels: InstalledHubModelsRepository = InstalledHubModelsRepository.get(context)
) : AutoCloseable {
    private val outputDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "outputs")

    @Volatile private var activeWorker: InferenceWorkerClient? = null
    @Volatile private var cancelRequested = false

    suspend fun executeGeneration(
        jobId: String,
        settings: CustomSdSettings,
        threads: Int,
        onUpdate: (EngineUpdate) -> Unit = {}
    ): MossGenerationResult = withContext(Dispatchers.IO) {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "任务 ID 无效" }
        cancelRequested = false
        currentCoroutineContext().ensureActive()
        settings.validate()
        val model = hubModels.find(settings.modelId)
            ?: throw IOException("所选图像模型不存在，请重新下载")
        val modelFile = hubModels.entryFile(model)
        check(modelFile.isFile) { "模型文件缺失：${modelFile.name}" }
        val arch = model.metadata["arch"]
        val llmFile = hubModels.resolveCompanionByMeta(model, "llmCompanion")
        val vaeFile = hubModels.resolveCompanionByMeta(model, "vaeCompanion")
        if (arch in MODULAR_IMAGE_ARCHS) {
            check(llmFile != null && llmFile.isFile) { "缺少共享文本编码器组件，请在模型页重新下载 ${model.name}" }
            check(vaeFile != null && vaeFile.isFile) { "缺少共享 VAE 解码器组件，请在模型页重新下载 ${model.name}" }
        }
        check(NativeEngineBridge.isSdEngineAvailable(context)) { "本机图像推理库（libsd_jni.so）未安装" }
        check(activeWorker == null) { "已有图像任务正在执行" }
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) throw IOException("无法创建输出目录")

        val requiredRamBytes = (model.totalBytes * 1.4).toLong().coerceIn(4_500_000_000L, 12_000_000_000L)
        MemoryGuardrails.checkBeforeRun(
            context = context,
            requiredTotalRamBytes = requiredRamBytes,
            requiredAvailRamBytes = (requiredRamBytes * 0.40).toLong().coerceAtLeast(1_800_000_000L),
            taskLabel = model.name
        )

        val outputFile = File(outputDirectory, "$jobId.png")
        val partialFile = File(outputDirectory, "$jobId.part.png")
        partialFile.delete()
        var refImageFile: File? = null
        var initImageFile: File? = null
        val worker = InferenceWorkerClient(context)
        try {
            if (settings.inputImageUri != null) {
                val copied = copyReferenceImage(settings.inputImageUri, jobId)
                if (model.metadata["supportsEdit"] == "true") {
                    refImageFile = copied
                } else {
                    initImageFile = copied
                }
            }
            val options = SdCliGenericImageOptions(
                modelFile = modelFile,
                output = partialFile,
                prompt = settings.prompt,
                negativePrompt = settings.negativePrompt,
                width = settings.width,
                height = settings.height,
                steps = settings.steps,
                seed = settings.seed,
                cfg = settings.cfg,
                threads = threads.coerceIn(2, 8),
                useOpenCl = settings.useOpenCl,
                llmFile = llmFile,
                vaeFile = vaeFile,
                refImage = refImageFile,
                initImage = initImageFile,
                strength = settings.strength,
                sampler = settings.sampler,
                vaeTiling = settings.vaeTiling,
                flashAttn = settings.useOpenCl
            )
            onUpdate(
                EngineUpdate(
                    0f,
                    "正在启动 ${model.name}",
                    "${modelFile.name} · ${if (settings.useOpenCl) "OpenCL" else "CPU"} · ${settings.sampler} · ${settings.steps} 步"
                )
            )
            if (refImageFile != null || initImageFile != null) onUpdate(EngineUpdate(null, "已载入参考图"))
            activeWorker = worker
            if (cancelRequested) throw CancellationException("已取消图像生成")

            val parser = SdCliProgressParser()
            val tailLock = Any()
            var outputTail = ""
            val openClCacheDir = File(context.cacheDir, "opencl-kernels").apply { mkdirs() }.absolutePath
            val exitCode = worker.run(
                jobId = jobId,
                engine = InferenceWorkerProtocol.ENGINE_SD,
                args = buildSdCliGenericImageCommand(options),
                openClCacheDir = openClCacheDir
            ) { chunk ->
                synchronized(tailLock) {
                    outputTail = (outputTail + chunk).takeLast(4_000)
                    parser.feed(chunk).forEach { event -> dispatchEvent(event, onUpdate) }
                }
            }
            synchronized(tailLock) {
                parser.finish().forEach { event -> dispatchEvent(event, onUpdate) }
            }
            currentCoroutineContext().ensureActive()
            if (cancelRequested) throw CancellationException("已取消图像生成")
            if (exitCode != 0 || partialFile.length() < 128L) {
                val detail = outputTail.replace(ANSI, "").trim()
                throw IOException(MemoryGuardrails.diagnoseNativeFailure(exitCode, detail, context))
            }
            if (outputFile.exists() || !partialFile.renameTo(outputFile)) throw IOException("无法保存生成图像")

            val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                ?: throw IOException("输出文件不是有效图片")
            val dims = try {
                bitmap.width to bitmap.height
            } finally {
                bitmap.recycle()
            }
            val result = MossGenerationResult(outputFile, dims.first, dims.second)
            onUpdate(EngineUpdate(1f, "图像已保存", outputFile.name))
            result
        } catch (error: Throwable) {
            outputFile.delete()
            if (error is CancellationException || cancelRequested) throw CancellationException("已取消图像生成")
            throw error
        } finally {
            activeWorker = null
            refImageFile?.delete()
            initImageFile?.delete()
            partialFile.delete()
            cancelRequested = false
        }
    }

    private fun copyReferenceImage(uri: Uri, jobId: String): File {
        val extension = context.contentResolver.getType(uri)?.substringAfter('/')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: "png"
        val target = File(context.cacheDir, "sd-ref-$jobId.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("无法读取所选参考图")
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 50L * 1024 * 1024) throw IOException("参考图不能超过 50 MB")
                    output.write(buffer, 0, count)
                }
            }
        }
        if (target.length() == 0L) throw IOException("所选参考图为空")
        return target
    }

    fun cancelGeneration() {
        cancelRequested = true
        activeWorker?.cancel()
    }

    override fun close() = cancelGeneration()

    private fun dispatchEvent(event: SdCliEvent, onUpdate: (EngineUpdate) -> Unit) {
        val seconds = event.finishedSeconds?.let { " · ${String.format(Locale.US, "%.2fs", it)}" }.orEmpty()
        when (event.stage) {
            SdCliStage.Loading -> onUpdate(EngineUpdate(null, "正在加载模型"))
            SdCliStage.TextEncoding -> onUpdate(EngineUpdate(0.05f, "文本编码完成$seconds"))
            SdCliStage.Denoising -> if (event.step > 0) {
                val progress = 0.1f + 0.75f * event.step.toFloat() / event.totalSteps
                val timing = event.secondsPerStep?.let { " · ${String.format(Locale.US, "%.2fs", it)}/步" }.orEmpty()
                onUpdate(EngineUpdate(progress, "去噪 ${event.step}/${event.totalSteps}", "采样 ${event.step}/${event.totalSteps}$timing"))
            }
            SdCliStage.Decoding -> onUpdate(EngineUpdate(0.88f, "正在解码图像", "采样完成$seconds"))
            SdCliStage.Saving -> onUpdate(EngineUpdate(if (event.completed) 0.99f else 0.96f, "正在保存图像", if (event.completed) "推理完成$seconds" else "解码完成$seconds"))
        }
    }

    private companion object {
        val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val MODULAR_IMAGE_ARCHS = setOf("z_image", "flux2_klein", "flux2_klein_9b", "flux3_klein", "ernie_image")
    }
}
