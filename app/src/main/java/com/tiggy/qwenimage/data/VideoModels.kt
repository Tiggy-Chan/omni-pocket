package com.tiggy.qwenimage.data

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class VideoStudioTab(val label: String) {
    Compose("创意渲染"),
    Gallery("作品展映")
}

data class VideoSettings(
    val prompt: String = "",
    val seed: Long = 42L,
    val fps: Int = 16,
    val numFrames: Int = 81, // 81 frames @ 16 fps = 5.06s
    val width: Int = 480,
    val height: Int = 832,
    val steps: Int = 3, // MobileWan 3-step DMD distillation
    val shift: Float = 5.0f,
    val highQuality: Boolean = true,
    val serverUrl: String = "http://127.0.0.1:8998",
    val inputImageUri: Uri? = null // Optional Image-to-Video conditioning
) {
    fun validate() {
        require(prompt.isNotBlank()) { "请输入视频提示词 (Prompt)" }
        require(steps in 1..10) { "MobileWan 步数推荐在 1 至 6 步之间" }
        require(fps in 8..30) { "帧率范围应在 8 至 30 FPS 之间" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("prompt", prompt)
        put("seed", seed)
        put("fps", fps)
        put("numFrames", numFrames)
        put("width", width)
        put("height", height)
        put("steps", steps)
        put("shift", shift.toDouble())
        put("highQuality", highQuality)
        put("serverUrl", serverUrl)
        put("inputImage", inputImageUri?.toString())
    }

    companion object {
        fun fromJson(o: JSONObject) = VideoSettings(
            prompt = o.optString("prompt", ""),
            seed = o.optLong("seed", 42L),
            fps = o.optInt("fps", 16),
            numFrames = o.optInt("numFrames", 81),
            width = o.optInt("width", 480),
            height = o.optInt("height", 832),
            steps = o.optInt("steps", 3),
            shift = o.optDouble("shift", 5.0).toFloat(),
            highQuality = o.optBoolean("highQuality", true),
            serverUrl = o.optString("serverUrl", "http://127.0.0.1:8998"),
            inputImageUri = o.optString("inputImage").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse)
        )
    }
}

data class VideoItem(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val videoPath: String,
    val thumbnailPath: String? = null,
    val durationSec: Float = 5.06f,
    val fps: Int = 16,
    val resolution: String = "480×832",
    val steps: Int = 3,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun fileExists(): Boolean = File(videoPath).exists()
}

data class VideoJob(
    val id: String = UUID.randomUUID().toString(),
    val settings: VideoSettings,
    val state: String = "queued", // queued, running, completed, failed, cancelled
    val progress: Float = 0f,
    val stage: String = "准备就绪",
    val resultPath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

data class VideoLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: String = "INFO" // INFO, SUCCESS, WARN, ERROR
)
