package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import java.util.concurrent.ConcurrentHashMap

class VideoEngine(private val context: Context) : AutoCloseable {
    private val videoDir = File(context.filesDir, "omni_video").apply { mkdirs() }
    private val thumbDir = File(context.filesDir, "omni_video_thumbs").apply { mkdirs() }
    private val externalFilesDir = context.getExternalFilesDir(null)
    private val localModelDir = File(externalFilesDir ?: context.filesDir, "models/wan22_turbo")
    private val activeClients = ConcurrentHashMap.newKeySet<WorkstationClient>()

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var cancelRequested = false

    fun runtimeStatus(): VideoRuntimeStatus {
        val missing = requiredLocalFiles().filter { !it.value.isFile || it.value.length() == 0L }.keys.toList()
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libsd_cli.so")
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

    fun generationBackend(): VideoBackend =
        if (runtimeStatus().localReady) VideoBackend.LocalWan else VideoBackend.Workstation

    suspend fun executeGeneration(
        job: VideoJob,
        backend: VideoBackend = generationBackend(),
        onUpdate: (EngineUpdate) -> Unit
    ): VideoItem = withContext(Dispatchers.IO) {
        try {
            job.settings.validate()
            if (cancelRequested) throw CancellationException("已取消视频生成")
            if (backend == VideoBackend.LocalWan) {
                executeOnDevice(job, onUpdate)
            } else {
                require(job.settings.inputImageUri == null) {
                    "图生视频需要本机 Wan 模型；当前工作站不支持参考图"
                }
                executeWorkstation(job, onUpdate)
            }
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
        activeProcess?.destroy()
        activeClients.forEach { it.close() }
    }

    override fun close() = cancelGeneration()

    private suspend fun executeOnDevice(
        job: VideoJob,
        onUpdate: (EngineUpdate) -> Unit
    ): VideoItem {
        val settings = job.settings
        settings.validateOnDevice()
        val files = requiredLocalFiles()
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libsd_cli.so")
        val videoId = "ondevice_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val outputFile = File(videoDir, "$videoId.webm")
        val partialFile = File(videoDir, "$videoId.part.webm")
        var initImageFile: File? = null
        var process: Process? = null

        try {
            initImageFile = settings.inputImageUri?.let { copyReferenceImage(it, videoId) }
            val options = SdCliVideoOptions(
                nativeLib = nativeLib,
                diffusionModel = files.getValue(DIT),
                textEncoder = files.getValue(T5),
                vae = files.getValue(VAE),
                output = partialFile,
                prompt = settings.prompt,
                width = settings.width,
                height = settings.height,
                frames = settings.numFrames,
                fps = settings.fps,
                steps = settings.steps,
                seed = settings.seed,
                shift = settings.shift,
                initImage = initImageFile
            )
            val command = buildSdCliVideoCommand(options)
            onUpdate(EngineUpdate(0f, "正在启动本机 Wan", "Wan2.2 · seed=${settings.seed} · ${settings.numFrames} 帧 @ ${settings.fps}fps · ${settings.steps} 步"))
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
                fallbackDuration = settings.numFrames.toFloat() / settings.fps
            )
            saveMetadata(item)
            onUpdate(EngineUpdate(1f, "视频已保存", "${outputFile.name} · ${item.durationSec.formatSeconds()} · ${outputFile.length() / 1024} KB"))
            return item
        } finally {
            activeProcess = null
            process?.let { if (it.isAlive) it.destroy() }
            initImageFile?.delete()
            partialFile.delete()
        }
    }

    private suspend fun executeWorkstation(
        job: VideoJob,
        onUpdate: (EngineUpdate) -> Unit
    ): VideoItem {
        val settings = job.settings
        val client = WorkstationClient(settings.serverUrl)
        activeClients += client
        try {
            if (cancelRequested) throw CancellationException("已取消视频生成")
            onUpdate(EngineUpdate(null, "正在连接视频工作站"))
            if (!client.isHealthy()) {
                throw IOException("无法连接视频工作站。请启动服务；USB 连接可运行 adb reverse tcp:8998 tcp:8998")
            }
            val payload = JSONObject().apply {
                put("prompt", settings.prompt)
                put("seed", settings.seed)
                put("fps", settings.fps)
                put("num_frames", settings.numFrames)
                put("width", settings.width)
                put("height", settings.height)
                put("steps", settings.steps)
                put("shift", settings.shift.toDouble())
                put("high_quality", settings.highQuality)
            }
            onUpdate(EngineUpdate(null, "工作站正在生成", "任务已提交，等待服务器返回"))
            val response = client.postJson("/generate", payload, 600_000)
            currentCoroutineContext().ensureActive()
            if (cancelRequested) throw CancellationException("已取消视频生成")

            val reference = response.getString("videoUrl")
            val extension = reference.substringAfterLast('.', "mp4").lowercase().let {
                if (it in VIDEO_EXTENSIONS) it else "mp4"
            }
            val videoId = "workstation_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val outputFile = File(videoDir, "$videoId.$extension")
            onUpdate(EngineUpdate(null, "正在接收视频"))
            client.download(reference, outputFile, 2L * 1024 * 1024 * 1024, "video")
            currentCoroutineContext().ensureActive()
            if (cancelRequested) throw CancellationException("已取消视频生成")
            val item = createItem(
                file = outputFile,
                settings = settings,
                backend = VideoBackend.Workstation,
                fallbackDuration = response.optDouble("durationSec", 0.0).toFloat(),
                fallbackFps = response.optInt("fps", settings.fps),
                fallbackResolution = response.optString("resolution", "${settings.width}×${settings.height}")
            )
            saveMetadata(item)
            onUpdate(EngineUpdate(1f, "视频已保存", outputFile.name))
            return item
        } finally {
            activeClients -= client
            client.close()
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
        const val T5 = "UMT5 文本编码器"
        const val VAE = "TAE 视频解码器"
        val VIDEO_EXTENSIONS = setOf("mp4", "webm")
        val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }
}

private fun Bitmap.toJpeg(): ByteArray = java.io.ByteArrayOutputStream().also { stream ->
    compress(Bitmap.CompressFormat.JPEG, 85, stream)
}.toByteArray()

private fun Float.formatSeconds(): String = if (this > 0) String.format(Locale.US, "%.2fs", this) else "时长未知"
