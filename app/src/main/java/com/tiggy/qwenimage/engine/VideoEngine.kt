package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tiggy.qwenimage.data.DeviceHardwareProbe
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.InstalledHubModelsRepository
import com.tiggy.qwenimage.data.VideoBackend
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoJob
import com.tiggy.qwenimage.data.VideoRuntimeStatus
import com.tiggy.qwenimage.data.VideoSettings
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
import java.util.UUID

class VideoEngine(
    private val context: Context,
    private val hubModels: InstalledHubModelsRepository = InstalledHubModelsRepository.get(context)
) : AutoCloseable {
    private val videoDir = File(context.filesDir, "omni_video").apply { mkdirs() }
    private val thumbDir = File(context.filesDir, "omni_video_thumbs").apply { mkdirs() }
    private val externalFilesDir = context.getExternalFilesDir(null)
    private val localModelDir = File(externalFilesDir ?: context.filesDir, "models/wan22_turbo")

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var cancelRequested = false

    fun isMiniMaxH3(modelId: String): Boolean =
        modelId.isNotBlank() && hubModels.find(modelId)?.metadata?.get("arch") == "minimax_h3"

    fun runtimeStatus(modelId: String = ""): VideoRuntimeStatus {
        val nativeLib = resolveSdCliBinary(context.applicationInfo.nativeLibraryDir)
        if (modelId.isNotBlank()) {
            val hubModel = hubModels.find(modelId)?.takeIf { it.type == HubModelType.VideoWan }
            if (hubModel == null) {
                return VideoRuntimeStatus(false, listOf("所选视频模型未安装"))
            }
            val missing = buildList {
                if (!hubModels.entryFile(hubModel).isFile) add(hubModel.entryFile.substringAfterLast('/'))
                if (hubModel.metadata["arch"] == "minimax_h3") {
                    if (hubModels.resolveCompanionByMeta(hubModel, "llmCompanion") == null) add(H3_TE)
                    if (hubModels.resolveCompanionByMeta(hubModel, "vaeCompanion") == null) add(H3_VAE)
                    if (hubModel.metadata["audioVaeCompanion"] != null &&
                        hubModels.resolveCompanionByMeta(hubModel, "audioVaeCompanion") == null
                    ) add(H3_AUDIO_VAE)
                    if (hubModel.metadata["piH3Adapter2Step"] != null &&
                        hubModels.resolveCompanionByMeta(hubModel, "piH3Adapter2Step") == null
                    ) add(H3_PI_2STEP)
                    if (hubModel.metadata["piH3Adapter4Step"] != null &&
                        hubModels.resolveCompanionByMeta(hubModel, "piH3Adapter4Step") == null
                    ) add(H3_PI_4STEP)
                } else {
                    if (hubModels.resolveCompanionByMeta(hubModel, "t5Companion") == null) add(T5)
                    if (hubModels.resolveCompanionByMeta(hubModel, "taeCompanion") == null) add(VAE)
                }
                if (!nativeLib.isFile) add("libsd_cli.so")
            }
            return VideoRuntimeStatus(missing.isEmpty(), missing)
        }
        // Exact sizes catch half-pushed files, which would otherwise fail only once sd-cli starts.
        val missing = requiredLocalFiles().filter { (name, file) ->
            !file.isFile || file.length() != EXPECTED_SIZES.getValue(name)
        }.keys.toList()
        val allMissing = if (!nativeLib.isFile) missing + "libsd_cli.so" else missing
        return VideoRuntimeStatus(allMissing.isEmpty(), allMissing)
    }

    fun preloadInitialVideos(): List<VideoItem> {
        val sourceDirs = listOfNotNull(videoDir, externalFilesDir?.let { File(it, "omni_video") })
            .filter { it.isDirectory }
            .distinctBy { it.absolutePath }
        val seenIds = mutableSetOf<String>()
        val items = mutableListOf<VideoItem>()

        sourceDirs.forEach { dir ->
            dir.listFiles { file -> file.isFile && file.extension.lowercase() in VIDEO_EXTENSIONS }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    val id = file.nameWithoutExtension
                    if (!seenIds.add(id)) return@forEach
                    val saved = runCatching {
                        VideoItem.fromJson(JSONObject(metadataFile(file).readText()))
                    }.getOrNull()
                    val thumbnail = ensureThumbnail(file)
                    val info = readMediaInfo(file)
                    items += if (saved == null) {
                        VideoItem(
                            id = id,
                            prompt = file.name,
                            videoPath = file.absolutePath,
                            thumbnailPath = thumbnail,
                            durationSec = info.durationSec,
                            fps = info.fps,
                            resolution = info.resolution,
                            fileSizeBytes = file.length(),
                            createdAt = file.lastModified()
                        )
                    } else {
                        saved.copy(
                            id = id,
                            videoPath = file.absolutePath,
                            thumbnailPath = thumbnail,
                            durationSec = info.durationSec.takeIf { it > 0 } ?: saved.durationSec,
                            fps = info.fps.takeIf { it > 0 } ?: saved.fps,
                            resolution = info.resolution.takeIf { it != "未知" } ?: saved.resolution,
                            fileSizeBytes = file.length(),
                            createdAt = saved.createdAt.takeIf { it > 0 } ?: file.lastModified()
                        )
                    }
                }
        }
        return items.sortedByDescending { it.createdAt }
    }

    suspend fun executeGeneration(
        job: VideoJob,
        onUpdate: (EngineUpdate) -> Unit
    ): VideoItem = withContext(Dispatchers.IO) {
        cancelRequested = false
        try {
            currentCoroutineContext().ensureActive()
            val isMiniMaxH3 = isMiniMaxH3(job.settings.modelId)
            job.settings.validate(isMiniMaxH3)
            if (cancelRequested) throw CancellationException("已取消视频生成")
            val status = runtimeStatus(job.settings.modelId)
            if (!status.localReady) throw IOException("本机视频模型缺少：${status.missingComponents.joinToString("、")}")
            executeOnDevice(job, onUpdate)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (cancelRequested) throw CancellationException("已取消视频生成") else throw error
        } finally {
            cancelRequested = false
        }
    }

    fun cancelGeneration() {
        cancelRequested = true
        val target = activeProcess
        if (target != null) Thread({ terminateProcessGracefully(target) }, "video-cancel").start()
    }

    override fun close() = cancelGeneration()

    private suspend fun executeOnDevice(
        job: VideoJob,
        onUpdate: (EngineUpdate) -> Unit
    ): VideoItem {
        val settings = job.settings
        val weights = resolveVideoWeights(settings.modelId)
        val isMiniMaxH3 = weights.arch == VideoModelArch.MINIMAX_H3
        settings.validateOnDevice(isMiniMaxH3)
        val nativeLib = resolveSdCliBinary(context.applicationInfo.nativeLibraryDir)
        val videoId = "ondevice_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val outputFile = File(videoDir, "$videoId.webm")
        val partialFile = File(videoDir, "$videoId.part.webm")
        var initImageFile: File? = null
        var process: Process? = null

        try {
            initImageFile = settings.inputImageUri?.let { copyReferenceImage(it, videoId) }
            val effectiveFps = if (isMiniMaxH3) 24 else settings.fps
            val piH3Adapter: File?
            val scheduler: String?
            val extraSampleArgs: String?
            if (isMiniMaxH3) {
                val useTwoStep = settings.steps <= 2
                piH3Adapter = if (useTwoStep) {
                    weights.piH3Adapter2Step ?: weights.piH3Adapter4Step
                } else {
                    weights.piH3Adapter4Step ?: weights.piH3Adapter2Step
                }
                if (piH3Adapter != null) {
                    scheduler = "piflow"
                    val maxRawT = if (useTwoStep) "0.93" else "0.96"
                    extraSampleArgs = "min_raw_t=0,max_raw_t=$maxRawT,final_step_size_scale=0.6"
                } else {
                    scheduler = null
                    extraSampleArgs = null
                }
            } else {
                piH3Adapter = null
                scheduler = null
                extraSampleArgs = null
            }
            val useDiskStreaming = isMiniMaxH3 && settings.diskStreaming
            val maxVramGb = if (useDiskStreaming) computeDiskStreamBudgetGb() else null

            val options = SdCliVideoOptions(
                nativeLib = nativeLib,
                diffusionModel = weights.dit,
                textEncoder = weights.textEncoder,
                vae = weights.vae,
                output = partialFile,
                prompt = settings.prompt,
                width = settings.width,
                height = settings.height,
                frames = settings.numFrames,
                fps = effectiveFps,
                steps = settings.steps,
                seed = settings.seed,
                shift = settings.shift,
                initImage = initImageFile,
                threads = settings.threads,
                negativePrompt = settings.negativePrompt,
                cfgScale = settings.cfgScale,
                useOpenCl = settings.useOpenCl,
                vaeTiling = settings.vaeTiling,
                arch = weights.arch,
                audioVae = weights.audioVae,
                piH3Adapter = piH3Adapter,
                useRefImageFlag = weights.isRef2Va,
                diskStreaming = useDiskStreaming,
                maxVramGb = maxVramGb,
                scheduler = scheduler,
                extraSampleArgs = extraSampleArgs
            )
            val command = buildSdCliVideoCommand(options)
            val accelLabel = buildList {
                add(if (settings.useOpenCl) "OpenCL DiT / CPU VAE" else "CPU")
                if (useDiskStreaming) add("Disk Stream")
                if (piH3Adapter != null) add("pi-H3")
            }.joinToString(" · ")
            onUpdate(EngineUpdate(0f, "正在启动 ${weights.label}", "${weights.label} · $accelLabel · seed=${settings.seed} · ${settings.numFrames} 帧 @ ${effectiveFps}fps · ${settings.steps} 步"))
            if (initImageFile != null) onUpdate(EngineUpdate(null, "已载入参考首帧"))

            val builder = ProcessBuilder(command)
            builder.environment()["LD_LIBRARY_PATH"] = "/vendor/lib64:${context.applicationInfo.nativeLibraryDir}"
            builder.environment()["GGML_OPENCL_KERNEL_CACHE_DIR"] = File(context.cacheDir, "opencl-kernels").apply { mkdirs() }.absolutePath
            builder.redirectErrorStream(true)
            process = builder.start()
            activeProcess = process
            if (cancelRequested) throw CancellationException("已取消视频生成")

            val parser = SdCliProgressParser()
            val input = process.inputStream
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
            val exitCode = process.waitFor()
            parser.finish().forEach { event -> dispatchEvent(event, onUpdate) }
            currentCoroutineContext().ensureActive()
            if (cancelRequested) throw CancellationException("已取消视频生成")
            if (exitCode != 0 || partialFile.length() < 1024L) {
                val detail = outputTail.replace(ANSI, "").trim().takeLast(600)
                throw IOException("本机视频生成失败（退出码 $exitCode）${if (detail.isEmpty()) "" else "\n$detail"}")
            }
            if (outputFile.exists() || !partialFile.renameTo(outputFile)) throw IOException("无法保存生成的视频")

            val item = createItem(
                file = outputFile,
                settings = settings,
                backend = VideoBackend.LocalWan,
                fallbackDuration = settings.numFrames.toFloat() / effectiveFps,
                fallbackFps = effectiveFps
            )
            saveMetadata(item)
            onUpdate(EngineUpdate(1f, "视频已保存", "${outputFile.name} · ${item.durationSec.formatSeconds()} · ${outputFile.length() / 1024} KB"))
            return item
        } finally {
            activeProcess = null
            terminateProcessGracefully(process)
            initImageFile?.delete()
            partialFile.delete()
        }
    }

    private data class ResolvedVideoWeights(
        val dit: File,
        val textEncoder: File,
        val vae: File,
        val label: String,
        val arch: VideoModelArch = VideoModelArch.WAN,
        val audioVae: File? = null,
        val piH3Adapter2Step: File? = null,
        val piH3Adapter4Step: File? = null,
        val isRef2Va: Boolean = false
    )

    private fun resolveVideoWeights(modelId: String): ResolvedVideoWeights {
        if (modelId.isNotBlank()) {
            val hubModel = hubModels.find(modelId)?.takeIf { it.type == HubModelType.VideoWan }
                ?: throw IOException("所选视频模型不存在，请重新下载")
            val dit = hubModels.entryFile(hubModel)
            if (hubModel.metadata["arch"] == "minimax_h3") {
                val llm = hubModels.resolveCompanionByMeta(hubModel, "llmCompanion")
                    ?: throw IOException("缺少共享 Qwen3-VL-32B 文本编码器，请在模型页重新下载")
                val vae = hubModels.resolveCompanionByMeta(hubModel, "vaeCompanion")
                    ?: throw IOException("缺少共享 MiniMax-H3 视频 VAE，请在模型页重新下载")
                val audioVae = hubModels.resolveCompanionByMeta(hubModel, "audioVaeCompanion")
                val adapter2 = hubModels.resolveCompanionByMeta(hubModel, "piH3Adapter2Step")
                val adapter4 = hubModels.resolveCompanionByMeta(hubModel, "piH3Adapter4Step")
                val isRef2Va = hubModel.metadata["h3Mode"] == "ref2va" ||
                    hubModel.entryFile.contains("ref2va", ignoreCase = true)
                return ResolvedVideoWeights(
                    dit = dit,
                    textEncoder = llm,
                    vae = vae,
                    label = hubModel.name,
                    arch = VideoModelArch.MINIMAX_H3,
                    audioVae = audioVae,
                    piH3Adapter2Step = adapter2,
                    piH3Adapter4Step = adapter4,
                    isRef2Va = isRef2Va
                )
            }
            val t5 = hubModels.resolveCompanionByMeta(hubModel, "t5Companion")
                ?: throw IOException("缺少共享 UMT5 文本编码器，请在模型页重新下载")
            val tae = hubModels.resolveCompanionByMeta(hubModel, "taeCompanion")
                ?: throw IOException("缺少共享 TAE 视频解码器，请在模型页重新下载")
            return ResolvedVideoWeights(dit, t5, tae, hubModel.name)
        }
        val files = requiredLocalFiles()
        return ResolvedVideoWeights(files.getValue(DIT), files.getValue(T5), files.getValue(VAE), "Wan2.2 Turbo")
    }

    private fun computeDiskStreamBudgetGb(): Float {
        val ramGb = runCatching { DeviceHardwareProbe.read(context).totalRamGb }.getOrDefault(12.0)
        return when {
            ramGb >= 20.0 -> 6.0f
            ramGb >= 14.0 -> 3.5f
            else -> 2.5f
        }
    }

    private fun dispatchEvent(event: SdCliEvent, onUpdate: (EngineUpdate) -> Unit) {
        val seconds = event.finishedSeconds?.let { " · ${it.formatSeconds()}" }.orEmpty()
        when (event.stage) {
            SdCliStage.Loading -> onUpdate(EngineUpdate(null, "正在加载模型"))
            SdCliStage.TextEncoding -> onUpdate(EngineUpdate(null, "文本编码完成$seconds"))
            SdCliStage.Denoising -> if (event.step > 0) {
                val progress = 0.1f + 0.75f * event.step.toFloat() / event.totalSteps
                val timing = event.secondsPerStep?.let { " · ${it.formatSeconds()}/步" }.orEmpty()
                onUpdate(EngineUpdate(progress, "去噪 ${event.step}/${event.totalSteps}", "采样 ${event.step}/${event.totalSteps}$timing"))
            }
            SdCliStage.Decoding -> onUpdate(EngineUpdate(0.88f, "正在解码视频", "采样完成$seconds"))
            SdCliStage.Saving -> onUpdate(EngineUpdate(if (event.completed) 0.99f else 0.96f, "正在保存视频", if (event.completed) "推理完成$seconds" else "解码完成$seconds"))
        }
    }

    private fun createItem(
        file: File,
        settings: VideoSettings,
        backend: VideoBackend,
        fallbackDuration: Float,
        fallbackFps: Int = settings.fps,
        fallbackResolution: String = "${settings.width}×${settings.height}"
    ): VideoItem {
        val info = readMediaInfo(file)
        val thumbnail = ensureThumbnail(file)
        return VideoItem(
            id = file.nameWithoutExtension,
            prompt = settings.prompt,
            videoPath = file.absolutePath,
            thumbnailPath = thumbnail,
            durationSec = info.durationSec.takeIf { it > 0 } ?: fallbackDuration,
            fps = info.fps.takeIf { it > 0 } ?: fallbackFps,
            resolution = info.resolution.takeIf { it != "未知" } ?: fallbackResolution,
            steps = settings.steps,
            fileSizeBytes = file.length(),
            createdAt = System.currentTimeMillis(),
            backend = backend
        )
    }

    private fun requiredLocalFiles(): Map<String, File> = mapOf(
        DIT to File(localModelDir, "Wan2_2-TI2V-5B-Turbo-Q4_0.gguf"),
        T5 to File(localModelDir, "umt5-xxl-encoder-Q3_K_S.gguf"),
        VAE to File(localModelDir, "taew2_2.safetensors")
    )

    private fun copyReferenceImage(uri: Uri, videoId: String): File {
        val extension = context.contentResolver.getType(uri)?.substringAfter('/')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: "img"
        val target = File(context.cacheDir, "video-init-$videoId.$extension")
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

    private fun ensureThumbnail(videoFile: File): String? {
        val output = File(thumbDir, "${videoFile.nameWithoutExtension}.jpg")
        if (output.isFile && output.length() > 0) return output.absolutePath
        val partial = File(thumbDir, "${videoFile.nameWithoutExtension}.part.jpg")
        return runCatching {
            val retriever = MediaMetadataRetriever()
            var bitmap: Bitmap? = null
            try {
                retriever.setDataSource(videoFile.absolutePath)
                bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val frame = bitmap ?: return@runCatching null
                FileOutputStream(partial).use { it.write(frame.toJpeg()) }
                if (output.exists()) output.delete()
                if (!partial.renameTo(output)) return@runCatching null
                output.absolutePath
            } finally {
                bitmap?.recycle()
                retriever.release()
            }
        }.getOrNull()
    }

    private fun readMediaInfo(file: File): MediaInfo = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull() ?: 0f
            val duration = durationMs / 1000
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()
            val fps = captureFps?.toInt() ?: if (durationMs > 0 && frameCount != null) (frameCount * 1000 / durationMs).toInt() else 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            MediaInfo(duration, fps, if (width > 0 && height > 0) "$width×$height" else "未知")
        } finally {
            retriever.release()
        }
    }.getOrDefault(MediaInfo())

    private fun saveMetadata(item: VideoItem) {
        val file = File(item.videoPath)
        val target = metadataFile(file)
        val partial = File(file.parentFile, "${file.nameWithoutExtension}.json.part")
        partial.writeText(item.toJson().toString())
        if (target.exists() && !target.delete()) throw IOException("无法更新视频元数据")
        if (!partial.renameTo(target)) throw IOException("无法保存视频元数据")
    }

    private fun metadataFile(video: File) = File(video.parentFile, "${video.nameWithoutExtension}.json")

    private data class MediaInfo(val durationSec: Float = 0f, val fps: Int = 0, val resolution: String = "未知")

    private companion object {
        const val DIT = "Wan2.2 DiT"
        const val T5 = "UMT5-XXL"
        const val VAE = "TAE-Wan2.2"
        const val H3_TE = "Qwen3-VL-32B"
        const val H3_VAE = "MiniMax-H3 Video VAE"
        const val H3_AUDIO_VAE = "MiniMax-H3 Audio VAE"
        const val H3_PI_2STEP = "pi-H3 2-step"
        const val H3_PI_4STEP = "pi-H3 4-step"
        val EXPECTED_SIZES = mapOf(
            DIT to 3_033_897_696L,
            T5 to 2_858_489_696L,
            VAE to 22_848_048L
        )
        val VIDEO_EXTENSIONS = setOf("mp4", "webm")
        val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }
}

private fun Bitmap.toJpeg(): ByteArray = java.io.ByteArrayOutputStream().also { stream ->
    compress(Bitmap.CompressFormat.JPEG, 85, stream)
}.toByteArray()

private fun Float.formatSeconds(): String = if (this > 0) String.format(Locale.US, "%.2fs", this) else "时长未知"
