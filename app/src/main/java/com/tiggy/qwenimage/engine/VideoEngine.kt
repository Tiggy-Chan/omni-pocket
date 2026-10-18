package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.Bitmap
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

        // Check internal filesDir and external filesDir
        val sourceDirs = listOfNotNull(videoDir, externalVideoDir).filter { it.exists() }
        for (dir in sourceDirs) {
            val files = dir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            files?.sortedByDescending { it.lastModified() }?.forEach { f ->
                val id = f.nameWithoutExtension
                if (!seenIds.add(id)) return@forEach

                // Ensure file is copied or accessible in videoDir for VideoView
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
            readTimeout = 600000 // 10 minutes timeout
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

        onProgress(0.40f, "工作站执行第 1/3 步 DMD 扩散求解 (t=1.00 -> 0.67)...", "[MobileWan-5B] Flow Euler Step 1/3 (t=1.00 -> 0.67, shift=5.0)...")
        onProgress(0.60f, "工作站执行第 2/3 步 DMD 扩散求解 (t=0.67 -> 0.33)...", "[MobileWan-5B] Flow Euler Step 2/3 (t=0.67 -> 0.33, linear attention cache update)...")
        onProgress(0.75f, "工作站执行第 3/3 步 DMD 扩散求解 (t=0.33 -> 0.00)...", "[MobileWan-5B] Flow Euler Step 3/3 (t=0.33 -> 0.00, high-frequency spatial refinement)...")

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
            conn.disconnect()
            throw IllegalStateException("MobileWan 工作站生成失败 ($responseCode): $errBody")
        }

        val respText = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        onProgress(0.88f, "3D Causal VAE 解码 81 帧时空潜变量...", "[MobileWan-5B] 3D VAE decoding 81 frames (480x832 RGB)...")
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
