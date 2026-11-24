package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.BitmapFactory
import com.tiggy.qwenimage.data.MossGenerationResult
import com.tiggy.qwenimage.data.MossModelRepository
import com.tiggy.qwenimage.data.MossSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class LocalMossEngine(
    private val context: Context,
    private val repository: MossModelRepository
) : AutoCloseable {
    private val outputDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "outputs")

    @Volatile
    private var activeWorker: InferenceWorkerClient? = null

    @Volatile
    private var cancelRequested = false

    suspend fun executeGeneration(
        jobId: String,
        settings: MossSettings,
        threadsOverride: Int? = null,
        onUpdate: (EngineUpdate) -> Unit = {}
    ): MossGenerationResult = withContext(Dispatchers.IO) {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "MOSS 任务 ID 无效" }
        cancelRequested = false
        currentCoroutineContext().ensureActive()
        settings.validateLocal()
        val modelStatus = repository.status()
        check(modelStatus.ready) { modelStatus.message }
        check(NativeEngineBridge.isSdEngineAvailable(context)) { "本机 MOSS 推理库未安装" }
        check(activeWorker == null) { "已有 MOSS 本机任务正在执行" }
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) throw IOException("无法创建 MOSS 输出目录")

        val outputFile = File(outputDirectory, "$jobId.png")
        val partialFile = File(outputDirectory, "$jobId.part.png")
        check(!outputFile.exists()) { "MOSS 输出文件已存在" }
        partialFile.delete()
        val effectiveThreads = (threadsOverride ?: settings.local.threads).coerceIn(2, 8)
        val options = SdCliMossOptions(
            diffusionModel = repository.artifactPath(MossModelRepository.SFT_FILE),
            textEncoder = repository.artifactPath(MossModelRepository.TEXT_ENCODER_FILE),
            vae = repository.artifactPath(MossModelRepository.VAE_FILE),
            tokenizer = repository.artifactPath(MossModelRepository.TOKENIZER_FILE),
            output = partialFile,
            prompt = settings.localModelPrompt(),
            negativePrompt = settings.negativePrompt,
            width = settings.local.width,
            height = settings.local.height,
            steps = settings.local.steps,
            seed = settings.seed,
            cfg = settings.local.cfg,
            shift = settings.shift,
            threads = effectiveThreads,
            useOpenCl = settings.local.useOpenCl,
            vaeTiling = settings.local.vaeTiling
        )
        val worker = InferenceWorkerClient(context)
        try {
            val backendLabel = if (settings.local.useOpenCl) "OpenCL" else "CPU"
            onUpdate(EngineUpdate(0f, "正在启动 MOSS 本机", "stable-diffusion.cpp · $backendLabel · ${settings.local.steps} 步"))
            activeWorker = worker
            if (cancelRequested) throw CancellationException("已取消 MOSS 本机生成")

            val parser = SdCliProgressParser()
            val tailLock = Any()
            var outputTail = ""
            val openClCacheDir = File(context.cacheDir, "opencl-kernels").apply { mkdirs() }.absolutePath
            val exitCode = worker.run(
                jobId = jobId,
                engine = InferenceWorkerProtocol.ENGINE_SD,
                args = buildSdCliMossCommand(options),
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
            if (cancelRequested) throw CancellationException("已取消 MOSS 本机生成")
            if (exitCode != 0 || partialFile.length() < 128L) {
                val detail = outputTail.replace(ANSI, "").trim().takeLast(600)
                throw IOException("MOSS 本机生成失败（退出码 $exitCode）${if (detail.isEmpty()) "" else "\n$detail"}")
            }
            if (outputFile.exists() || !partialFile.renameTo(outputFile)) throw IOException("无法保存 MOSS 图像")

            val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath) ?: throw IOException("本机输出不是可识别的图片")
            val dimensions = try {
                bitmap.width to bitmap.height
            } finally {
                bitmap.recycle()
            }
            if (dimensions != (settings.local.width to settings.local.height)) {
                outputFile.delete()
                throw IOException("MOSS 输出尺寸 ${dimensions.first}×${dimensions.second} 与请求 ${settings.local.width}×${settings.local.height} 不一致")
            }
            val result = MossGenerationResult(outputFile, dimensions.first, dimensions.second)
            onUpdate(EngineUpdate(1f, "MOSS 本机图像已保存", outputFile.name))
            result
        } catch (error: Throwable) {
            outputFile.delete()
            if (error is CancellationException || cancelRequested) throw CancellationException("已取消 MOSS 本机生成")
            throw error
        } finally {
            activeWorker = null
            partialFile.delete()
            cancelRequested = false
        }
    }

    fun cancelGeneration() {
        cancelRequested = true
        activeWorker?.cancel()
    }

    override fun close() = cancelGeneration()

    private fun dispatchEvent(event: SdCliEvent, onUpdate: (EngineUpdate) -> Unit) {
        val seconds = event.finishedSeconds?.let { " · ${it.formatSeconds()}" }.orEmpty()
        when (event.stage) {
            SdCliStage.Loading -> onUpdate(EngineUpdate(null, "正在加载 MOSS 模型"))
            SdCliStage.TextEncoding -> onUpdate(EngineUpdate(0.05f, "文本编码完成$seconds"))
            SdCliStage.Denoising -> if (event.step > 0) {
                val progress = 0.1f + 0.75f * event.step.toFloat() / event.totalSteps
                val timing = event.secondsPerStep?.let { " · ${it.formatSeconds()}/步" }.orEmpty()
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

private fun Float.formatSeconds(): String = if (this > 0) String.format(Locale.US, "%.2fs", this) else "耗时未知"
