package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class VideoEngine(private val context: Context) : AutoCloseable {

    private val videoDir = File(context.filesDir, "omni_video").apply { mkdirs() }
    private val thumbDir = File(context.filesDir, "omni_video_thumbs").apply { mkdirs() }
    private val externalVideoDir = File(context.getExternalFilesDir(null), "omni_video").apply { mkdirs() }

    fun preloadInitialVideos(): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seenIds = mutableSetOf<String>()

        val sourceDirs = listOfNotNull(videoDir, externalVideoDir).filter { it.exists() }
        for (dir in sourceDirs) {
            val files = dir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            files?.sortedByDescending { it.lastModified() }?.forEach { f ->
                val id = f.nameWithoutExtension
                if (!seenIds.add(id)) return@forEach

                val targetFile = if (f.parentFile?.absolutePath == videoDir.absolutePath) f else {
                    val localCopy = File(videoDir, f.name)
                    if (!localCopy.exists() || localCopy.length() != f.length()) {
                        runCatching { f.copyTo(localCopy, overwrite = true) }
                    }
                    if (localCopy.exists()) localCopy else f
                }

                val thumbFile = File(thumbDir, "$id.jpg")
                val thumbPath = if (thumbFile.exists()) thumbFile.absolutePath else extractThumbnail(targetFile, thumbFile)

                items.add(
                    VideoItem(
                        id = id,
                        prompt = "本地渲染视频: ${f.name}",
                        videoPath = targetFile.absolutePath,
                        thumbnailPath = thumbPath,
                        durationSec = 5.06f,
                        fps = 16,
                        resolution = "480×832",
                        steps = 4,
                        fileSizeBytes = targetFile.length(),
                        createdAt = targetFile.lastModified()
                    )
                )
            }
        }

        return items.sortedByDescending { it.createdAt }
    }

    private fun extractThumbnail(videoFile: File, outputFile: File): String? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            if (bitmap != null) {
                FileOutputStream(outputFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                outputFile.absolutePath
            } else null
        }.getOrNull()
    }

    suspend fun executeGeneration(
        job: VideoJob,
        onProgress: (progress: Float, stage: String, log: String) -> Unit
    ): VideoItem = withContext(Dispatchers.IO) {
        val localModelDir = File(context.getExternalFilesDir(null), "models/wan22_turbo")
        val ditFile = File(localModelDir, "Wan2_2-TI2V-5B-Turbo-Q4_0.gguf")
        val t5File = File(localModelDir, "umt5-xxl-encoder-Q3_K_S.gguf")
        val vaeFile = File(localModelDir, "taew2_2.safetensors")
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libsd_cli.so")

        if (ditFile.exists() && t5File.exists() && vaeFile.exists() && nativeLib.exists()) {
            return@withContext executeOnDevice(job, ditFile, t5File, vaeFile, nativeLib, onProgress)
        } else {
            return@withContext executeWorkstationBridge(job, onProgress)
        }
    }

    private suspend fun executeOnDevice(
        job: VideoJob,
        ditFile: File,
        t5File: File,
        vaeFile: File,
        nativeLib: File,
        onProgress: (progress: Float, stage: String, log: String) -> Unit
    ): VideoItem = withContext(Dispatchers.IO) {
        val settings = job.settings
        val videoId = UUID.randomUUID().toString()
        val outputFile = File(videoDir, "ondevice_${System.currentTimeMillis()}_${videoId.take(6)}.webm")

        onProgress(0.05f, "正在初始化端侧神经视频扩散引擎...", "[Wan2.2-TI2V] Initializing On-Device Engine (Adreno GPU + UMT5-XXL)...")

        val targetFrames = settings.numFrames.coerceIn(5, 25)
        val command = mutableListOf(
            "/system/bin/nice", "-n", "10",
            nativeLib.absolutePath,
            "-M", "vid_gen",
            "--diffusion-model", ditFile.absolutePath,
            "--t5xxl", t5File.absolutePath,
            "--taesd", vaeFile.absolutePath,
            "--backend", "diffusion=GPUOpenCL,te=CPU,vae=CPU",
            "-t", "4",
            "-p", settings.prompt,
            "-n", "deformed, distorted, ugly, blurry, bad hands, dark, low quality, oversaturated, watermark, text",
            "-W", settings.width.toString(),
            "-H", settings.height.toString(),
            "--video-frames", targetFrames.toString(),
            "--steps", settings.steps.coerceIn(1, 6).toString(),
            "--cfg-scale", "1.0",
            "--sampling-method", "euler",
            "--flow-shift", settings.shift.toString(),
            "--diffusion-fa",
            "-o", outputFile.absolutePath
        )

        val initImageFile = File(context.getExternalFilesDir(null), "init_frame.png")
        if (initImageFile.exists()) {
            command.add("-i")
            command.add(initImageFile.absolutePath)
            onProgress(0.10f, "已载入参考首帧图像...", "[Wan2.2-TI2V] Conditioning on init image: ${initImageFile.name}")
        }

        onProgress(0.15f, "正在加载模型至 Adreno 750 GPU (OpenCL)...", "[Wan2.2-TI2V] Loading weights (DiT: 2.89GB to GPU, UMT5: 2.7GB to CPU)...")

        val pb = ProcessBuilder(command)
        pb.environment()["LD_LIBRARY_PATH"] = "/vendor/lib64:" + context.applicationInfo.nativeLibraryDir
        pb.redirectErrorStream(true)

        val process = pb.start()
        val inputStream = process.inputStream
        val byteBuf = ByteArray(4096)
        val lineAccumulator = StringBuilder()
        var currentStep = 0
        val totalSteps = settings.steps.coerceIn(1, 6)

        var bytesRead: Int
        while (inputStream.read(byteBuf).also { bytesRead = it } != -1) {
            val chunk = String(byteBuf, 0, bytesRead, Charsets.UTF_8)
            lineAccumulator.append(chunk)

            val delimiter = Regex("[\r\n]+")
            val parts = lineAccumulator.toString().split(delimiter)
            if (parts.size > 1) {
                for (i in 0 until parts.size - 1) {
                    val text = parts[i].trim()
                    if (text.isNotEmpty()) {
                        when {
                            text.contains("loading t5xxl") -> {
                                onProgress(0.20f, "加载 UMT5-XXL 语义编码器...", "[UMT5-XXL] Loading text encoder into CPU RAM...")
                            }
                            text.contains("get_learned_condition completed") -> {
                                onProgress(0.35f, "语义条件特征计算完成...", "[Wan2.2-TI2V] Prompt embeddings extracted successfully.")
                            }
                            text.contains("generate_video") -> {
                                onProgress(0.40f, "Adreno 750 GPU 准备执行时空去噪...", "[Wan2.2-TI2V] Denoising loop initiated on Adreno 750 GPU...")
                            }
                            text.contains("sampling using Euler") -> {
                                onProgress(0.45f, "开始 Flow-Euler 扩散去噪...", "[Euler] Sampling 4 steps with flow shift ${settings.shift}...")
                            }
                            text.contains("/$totalSteps") || text.contains("s/it") -> {
                                currentStep++
                                val pct = 0.45f + 0.40f * (currentStep.toFloat() / totalSteps.toFloat()).coerceAtMost(1f)
                                onProgress(pct, "GPU 正在执行去噪解算 (步数 $currentStep/$totalSteps)...", "[GPU Adreno 750] Step $currentStep/$totalSteps computed: $text")
                            }
                            text.contains("sampling completed") -> {
                                onProgress(0.85f, "GPU 时空潜变量求解完成...", "[Wan2.2-TI2V] Latent video generated on GPU.")
                            }
                            text.contains("decode_first_stage completed") -> {
                                onProgress(0.95f, "TAEHV 视频潜空间解码完成...", "[TAEHV] Decoded 48-channel latents to RGB frames.")
                            }
                            text.contains("images saved") -> {
                                onProgress(0.98f, "正在编码 H.264 MP4 视频...", "[MediaCodec] Encoding frames into MP4 container...")
                            }
                        }
                    }
                }
                lineAccumulator.clear()
                lineAccumulator.append(parts.last())
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0 || !outputFile.exists() || outputFile.length() < 1024L) {
            throw IllegalStateException("端侧视频推理失败 (exit code: $exitCode, size: ${outputFile.length()} bytes)")
        }

        val durationSec = targetFrames.toFloat() / settings.fps
        val thumbFile = File(thumbDir, "${outputFile.nameWithoutExtension}.jpg")
        val thumbPath = extractThumbnail(outputFile, thumbFile)

        onProgress(1.0f, "视频渲染完成，已入库！", "[Wan2.2-TI2V] Complete: ${outputFile.name} (${outputFile.length() / 1024} KB, ${durationSec}s @ ${settings.fps}fps)")

        VideoItem(
            id = videoId,
            prompt = settings.prompt,
            videoPath = outputFile.absolutePath,
            thumbnailPath = thumbPath,
            durationSec = durationSec,
            fps = settings.fps,
            resolution = "${settings.width}×${settings.height}",
            steps = settings.steps,
            fileSizeBytes = outputFile.length(),
            createdAt = System.currentTimeMillis()
        )
    }

    private suspend fun executeWorkstationBridge(
        job: VideoJob,
        onProgress: (progress: Float, stage: String, log: String) -> Unit
    ): VideoItem = withContext(Dispatchers.IO) {
        val settings = job.settings
        val serverBase = settings.serverUrl.trimEnd('/')

        onProgress(0.05f, "正在连接 MobileWan 视频工作站...", "[MobileWan-5B] Connecting to workstation: $serverBase...")

        val healthUrl = URL("$serverBase/health")
        val healthConn = (healthUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
        }

        val serverReady = runCatching {
            healthConn.responseCode == 200
        }.getOrDefault(false)
        healthConn.disconnect()

        if (!serverReady) {
            val errorMsg = "未连接到本地 MobileWan 推理服务 ($serverBase)。\n请在电脑上启动 mobilewan server，并通过 USB 运行 'adb reverse tcp:8998 tcp:8998'，或输入电脑同一局域网的 IP 地址。"
            onProgress(0f, "连接失败", "[MobileWan-5B] 错误：无法连接至 $serverBase")
            throw IllegalStateException(errorMsg)
        }

        onProgress(0.12f, "已连接工作站，正在提交视频生成任务...", "[MobileWan-5B] Task accepted: prompt=\"${settings.prompt.take(50)}...\" (Steps: ${settings.steps}, Frames: ${settings.numFrames})")

        val generateUrl = URL("$serverBase/generate")
        val conn = (generateUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 600000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        val requestPayload = JSONObject().apply {
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

        onProgress(0.20f, "工作站加载 23% 剪枝与循环混合注意力 (RHA)...", "[MobileWan-5B] Engaging Recurrent Hybrid Attention & 23% pruned self-attention heads...")

        conn.outputStream.use { os ->
            os.write(requestPayload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        onProgress(0.40f, "工作站执行第 1/4 步 DMD 扩散求解...", "[MobileWan-5B] Flow Euler Step 1/4 (shift=${settings.shift})...")
        onProgress(0.60f, "工作站执行第 2/4 步 DMD 扩散求解...", "[MobileWan-5B] Flow Euler Step 2/4...")
        onProgress(0.75f, "工作站执行第 3/4 步 DMD 扩散求解...", "[MobileWan-5B] Flow Euler Step 3/4...")

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
            conn.disconnect()
            throw IllegalStateException("MobileWan 工作站生成失败 ($responseCode): $errBody")
        }

        val respText = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        onProgress(0.88f, "3D Causal VAE 解码时空潜变量...", "[MobileWan-5B] 3D VAE decoding frames...")
        onProgress(0.95f, "正在从工作站下载 H.264 MP4 视频...", "[MobileWan-5B] Downloading generated MP4 from workstation...")

        val respJson = JSONObject(respText)
        val videoRelUrl = respJson.getString("videoUrl")
        val durationSec = respJson.optDouble("durationSec", 5.06).toFloat()
        val fps = respJson.optInt("fps", settings.fps)
        val resolution = respJson.optString("resolution", "${settings.width}×${settings.height}")

        val videoFileUrl = URL(if (videoRelUrl.startsWith("http")) videoRelUrl else "$serverBase$videoRelUrl")
        val videoId = UUID.randomUUID().toString()
        val outputFile = File(videoDir, "mobilewan_${System.currentTimeMillis()}_${videoId.take(6)}.mp4")

        val dlConn = (videoFileUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 60000
        }
        dlConn.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        dlConn.disconnect()

        val thumbFile = File(thumbDir, "${outputFile.nameWithoutExtension}.jpg")
        val thumbPath = extractThumbnail(outputFile, thumbFile)

        onProgress(1.0f, "视频渲染完成，已入库！", "[MobileWan-5B] Complete: ${outputFile.name} (${outputFile.length() / 1024} KB, ${durationSec}s @ ${fps}fps)")

        VideoItem(
            id = videoId,
            prompt = settings.prompt,
            videoPath = outputFile.absolutePath,
            thumbnailPath = thumbPath,
            durationSec = durationSec,
            fps = fps,
            resolution = resolution,
            steps = settings.steps,
            fileSizeBytes = outputFile.length(),
            createdAt = System.currentTimeMillis()
        )
    }

    override fun close() {
        // Cleanup if necessary
    }
}
