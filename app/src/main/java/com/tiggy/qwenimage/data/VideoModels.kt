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
    val negativePrompt: String = DEFAULT_VIDEO_NEGATIVE_PROMPT,
    val seed: Long = 42L,
    val fps: Int = 16,
    val numFrames: Int = 25,
    val width: Int = 480,
    val height: Int = 832,
    val steps: Int = 4,
    val cfgScale: Float = 1.0f,
    val shift: Float = 5.0f,
    val highQuality: Boolean = true,
    val useOpenCl: Boolean = true,
    val vaeTiling: Boolean = false,
    val threads: Int = 6,
    /** Empty string selects the built-in Wan2.2-TI2V-5B-Turbo package; otherwise a Hub VideoWan model ID. */
    val modelId: String = "",
    val inputImageUri: Uri? = null
) {
    fun validate() {
        require(prompt.isNotBlank()) { "请输入视频提示词" }
        require(numFrames in 5..81 && (numFrames - 1) % 4 == 0) { "视频帧数必须为 5–81 且满足 4n+1（如 9、17、25、33）" }
        require(steps in 1..20) { "视频步数必须为 1–20" }
        require(fps in 8..30) { "帧率必须为 8–30 FPS" }
        require(cfgScale.isFinite() && cfgScale in 0.5f..15.0f) { "视频 CFG 必须在 0.5 到 15 之间" }
        require(shift.isFinite() && shift in 1.0f..12.0f) { "流偏移必须在 1 到 12 之间" }
        require(threads in 2..8) { "视频线程数必须为 2–8" }
        require(width > 0 && height > 0 && width % 32 == 0 && height % 32 == 0) { "视频尺寸必须为 32 的倍数" }
    }

    fun validateOnDevice() {
        validate()
        require(numFrames <= 41) { "本机最多生成 41 帧" }
        require(steps <= 12) { "本机最多使用 12 步" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("seed", seed)
        put("fps", fps)
        put("numFrames", numFrames)
        put("width", width)
        put("height", height)
        put("steps", steps)
        put("cfgScale", cfgScale.toDouble())
        put("shift", shift.toDouble())
        put("highQuality", highQuality)
        put("useOpenCl", useOpenCl)
        put("vaeTiling", vaeTiling)
        put("threads", threads)
        put("modelId", modelId)
        put("inputImage", inputImageUri?.toString())
    }

    companion object {
        const val DEFAULT_VIDEO_NEGATIVE_PROMPT =
            "deformed, distorted, ugly, blurry, bad hands, dark, low quality, oversaturated, watermark, text"

        fun snapValidFrameCount(raw: Int, max: Int = 41): Int {
            val clamped = raw.coerceIn(5, max)
            val k = ((clamped - 1) + 2) / 4
            return (k * 4 + 1).coerceIn(5, max)
        }

        fun fromJson(o: JSONObject) = VideoSettings(
            prompt = o.optString("prompt", ""),
            negativePrompt = o.optString("negativePrompt", DEFAULT_VIDEO_NEGATIVE_PROMPT),
            seed = o.optLong("seed", 42L),
            fps = o.optInt("fps", 16),
            numFrames = snapValidFrameCount(o.optInt("numFrames", 25)),
            width = o.optInt("width", 480),
            height = o.optInt("height", 832),
            steps = o.optInt("steps", 4).coerceIn(1, 12),
            cfgScale = o.optDouble("cfgScale", 1.0).toFloat().coerceIn(0.5f, 15.0f),
            shift = o.optDouble("shift", 5.0).toFloat().coerceIn(1.0f, 12.0f),
            highQuality = o.optBoolean("highQuality", true),
            useOpenCl = o.optBoolean("useOpenCl", true),
            vaeTiling = o.optBoolean("vaeTiling", false),
            threads = o.optInt("threads", 6).coerceIn(2, 8),
            modelId = o.optString("modelId", ""),
            inputImageUri = o.optString("inputImage").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse)
        )
    }
}

typealias VideoBackend = GenerationBackend

data class VideoRuntimeStatus(
    val localReady: Boolean,
    val missingComponents: List<String>
)

data class VideoItem(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val videoPath: String,
    val thumbnailPath: String? = null,
    val durationSec: Float = 0f,
    val fps: Int = 0,
    val resolution: String = "未知",
    val steps: Int = 4,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val backend: VideoBackend = VideoBackend.LocalWan
) {
    fun fileExists(): Boolean = File(videoPath).exists()
    fun mimeType(): String = when (File(videoPath).extension.lowercase()) {
        "webm" -> "video/webm"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("prompt", prompt)
        put("videoPath", videoPath)
        put("thumbnailPath", thumbnailPath ?: "")
        put("durationSec", durationSec.toDouble())
        put("fps", fps)
        put("resolution", resolution)
        put("steps", steps)
        put("fileSizeBytes", fileSizeBytes)
        put("createdAt", createdAt)
        put("backend", backend.name)
    }

    companion object {
        fun fromJson(o: JSONObject): VideoItem = VideoItem(
            id = o.optString("id", UUID.randomUUID().toString()),
            prompt = o.optString("prompt"),
            videoPath = o.optString("videoPath"),
            thumbnailPath = o.optString("thumbnailPath").takeIf { it.isNotBlank() },
            durationSec = o.optDouble("durationSec", 0.0).toFloat(),
            fps = o.optInt("fps", 0),
            resolution = o.optString("resolution", "未知"),
            steps = o.optInt("steps", 4),
            fileSizeBytes = o.optLong("fileSizeBytes"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            backend = runCatching { VideoBackend.valueOf(o.optString("backend")) }.getOrDefault(VideoBackend.LocalWan)
        )
    }
}

data class VideoJob(
    val id: String = UUID.randomUUID().toString(),
    val settings: VideoSettings,
    val state: String = "queued",
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
    val level: String = "INFO"
)
