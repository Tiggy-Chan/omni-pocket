package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.BitmapFactory
import com.tiggy.qwenimage.data.InstalledHubModel
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.MossGenerationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
    val useOpenCl: Boolean = false
) {
    fun validate() {
        require(modelId.isNotBlank()) { "请先选择已下载的图像模型" }
        require(prompt.isNotBlank()) { "请输入提示词" }
        require(width in 256..1024 && height in 256..1024 && width % 64 == 0 && height % 64 == 0) {
            "宽高必须为 256–1024 内的 64 倍数"
        }
        require(steps in 1..60) { "步数必须为 1–60" }
        require(cfg.isFinite() && cfg in 0.0..20.0) { "CFG 必须在 0 到 20 之间" }
        require(seed >= 0L) { "Seed 不能为负数" }
    }

    companion object {
        fun defaultsFor(model: InstalledHubModel, current: CustomSdSettings): CustomSdSettings {
            val lower = (model.repo + " " + model.entryFile).lowercase(Locale.ROOT)
            val isTurbo = lower.contains("turbo") || lower.contains("lightning") || lower.contains("lcm")
            return current.copy(
                modelId = model.id,
                steps = if (isTurbo) 4 else 20,
                cfg = if (isTurbo) 1.0 else 7.0
            )
        }
    }
}

/**
 * Executes any single-file Stable Diffusion / SDXL / Turbo checkpoint downloaded from
 * Hugging Face / hf-mirror via the unified stable-diffusion.cpp binary (libsd_cli.so).
 */
class LocalSdImageEngine(
    private val context: Context,
    private val hubModels: InstalledHubModelsRepository = InstalledHubModelsRepository.get(context)
) : AutoCloseable {
    private val outputDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "outputs")

    @Volatile private var activeProcess: Process? = null
    @Volatile private var cancelRequested = false

    suspend fun executeGeneration(
        jobId: String,
        settings: CustomSdSettings,
        threads: Int,
        onUpdate: (EngineUpdate) -> Unit = {}
    ): MossGenerationResult = withContext(Dispatchers.IO) {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "任务 ID 无效" }
        if (cancelRequested) throw CancellationException("已取消图像生成")
        settings.validate()
        val model = hubModels.find(settings.modelId)
            ?: throw IOException("所选图像模型不存在，请重新下载")
        val modelFile = hubModels.entryFile(model)
        check(modelFile.isFile) { "模型文件缺失：${modelFile.name}" }
        val nativeLib = resolveSdCliBinary(context.applicationInfo.nativeLibraryDir)
        check(nativeLib.isFile) { "本机图像推理库（libsd_cli.so）未安装" }
        check(activeProcess == null) { "已有图像任务正在执行" }
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) throw IOException("无法创建输出目录")

        val outputFile = File(outputDirectory, "$jobId.png")
        val partialFile = File(outputDirectory, "$jobId.part.png")
        partialFile.delete()

        val options = SdCliGenericImageOptions(
            nativeLib = nativeLib,
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
            useOpenCl = settings.useOpenCl
        )
        var process: Process? = null
        try {
            onUpdate(
                EngineUpdate(
                    0f,
                    "正在启动 ${model.name}",
                    "${modelFile.name} · ${if (settings.useOpenCl) "OpenCL" else "CPU"} · ${settings.steps} 步"
                )
            )
            val builder = ProcessBuilder(buildSdCliGenericImageCommand(options))
            builder.environment()["LD_LIBRARY_PATH"] = "/vendor/lib64:${context.applicationInfo.nativeLibraryDir}"
            builder.environment()["GGML_OPENCL_KERNEL_CACHE_DIR"] =
                File(context.cacheDir, "opencl-kernels").apply { mkdirs() }.absolutePath
            builder.redirectErrorStream(true)
            val launched = builder.start()
            process = launched
            activeProcess = launched
            if (cancelRequested) throw CancellationException("已取消图像生成")

            val parser = SdCliProgressParser()
            val input = launched.inputStream
            val buffer = ByteArray(4096)
            var outputTail = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                val chunk = String(buffer, 0, count, Charsets.UTF_8)
                outputTail = (outputTail + chunk).takeLast(4_000)
                parser.feed(chunk).forEach { event -> dispatchEvent(event, onUpdate) }
            }
            val exitCode = launched.waitFor()
            parser.finish().forEach { event -> dispatchEvent(event, onUpdate) }
            currentCoroutineContext().ensureActive()
            if (cancelRequested) throw CancellationException("已取消图像生成")
            if (exitCode != 0 || partialFile.length() < 128L) {
                val detail = outputTail.replace(ANSI, "").trim().takeLast(600)
                throw IOException("图像生成失败（退出码 $exitCode）${if (detail.isEmpty()) "" else "\n$detail"}")
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
            activeProcess = null
            process?.let { if (it.isAlive) it.destroy() }
            partialFile.delete()
            cancelRequested = false
        }
    }

    fun cancelGeneration() {
        cancelRequested = true
        activeProcess?.destroy()
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
    }
}
