package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class AppDomain(val title: String, val subtitle: String) {
    ImageStudio("绘卷 · 生图工坊", "Qwen-Image-2.1 Turbo (8-step)"),
    MusicStudio("乐坊 · 音乐工坊", "YuE2-3B 48kHz 端侧全乐章")
}

enum class MusicStudioTab(val label: String) {
    Compose("创作编曲"),
    Playlist("成果曲库"),
    Score("乐理简谱")
}

enum class CoTMode(val tag: String, val label: String, val description: String) {
    Melody("melody", "旋律线规划 (melody)", "推荐：YuE2推演符号旋律，音节精准对齐，绝无漏唱"),
    Full("full", "和声全规划 (full)", "生成旋律线及和弦走向，配器丰富度高"),
    Off("off", "直接声学 (off)", "跳过乐理规划直接自回归生成音频，生成迅速")
}

data class MusicSettings(
    val title: String = "",
    val style: String = "",
    val lyrics: String = "",
    val bpm: Int = 112,
    val cotMode: CoTMode = CoTMode.Melody,
    val seed: Long = 42L,
    val targetDurationSec: Int = 90,
    val odeSteps: Int = 32,
    val guidanceScale: Float = 1.0f,
    val serverUrl: String = "http://127.0.0.1:8999"
) {
    fun validate() {
        require(style.isNotBlank()) { "请输入音乐风格描述 (Style)" }
        require(lyrics.isNotBlank()) { "请输入歌词内容 (Lyrics)" }
        require(bpm in 50..220) { "BPM 范围应在 50 至 220 之间" }
        require(odeSteps in 8..64) { "ODE 步数必须在 8 至 64 之间" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("style", style)
        put("lyrics", lyrics)
        put("bpm", bpm)
        put("cotMode", cotMode.name)
        put("seed", seed)
        put("targetDurationSec", targetDurationSec)
        put("odeSteps", odeSteps)
        put("guidanceScale", guidanceScale.toDouble())
        put("serverUrl", serverUrl)
    }

    companion object {
        fun fromJson(o: JSONObject): MusicSettings = MusicSettings(
            title = o.optString("title", ""),
            style = o.optString("style", ""),
            lyrics = o.optString("lyrics", ""),
            bpm = o.optInt("bpm", 112),
            cotMode = runCatching { CoTMode.valueOf(o.optString("cotMode")) }.getOrDefault(CoTMode.Melody),
            seed = o.optLong("seed", 42L),
            targetDurationSec = o.optInt("targetDurationSec", 90),
            odeSteps = o.optInt("odeSteps", 32),
            guidanceScale = o.optDouble("guidanceScale", 1.0).toFloat(),
            serverUrl = o.optString("serverUrl", "http://127.0.0.1:8999")
        )
    }
}

data class MusicTrack(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val style: String,
    val lyrics: String,
    val cotMode: CoTMode,
    val abcScore: String? = null,
    val audioPath: String,
    val durationSec: Float,
    val sampleRate: Int = 48000,
    val bpm: Int = 112,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("style", style)
        put("lyrics", lyrics)
        put("cotMode", cotMode.name)
        put("abcScore", abcScore ?: "")
        put("audioPath", audioPath)
        put("durationSec", durationSec.toDouble())
        put("sampleRate", sampleRate)
        put("bpm", bpm)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): MusicTrack = MusicTrack(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "未命名曲目"),
            style = o.optString("style", ""),
            lyrics = o.optString("lyrics", ""),
            cotMode = runCatching { CoTMode.valueOf(o.optString("cotMode")) }.getOrDefault(CoTMode.Melody),
            abcScore = o.optString("abcScore").takeIf { it.isNotBlank() },
            audioPath = o.optString("audioPath", ""),
            durationSec = o.optDouble("durationSec", 0.0).toFloat(),
            sampleRate = o.optInt("sampleRate", 48000),
            bpm = o.optInt("bpm", 112),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

data class MusicJob(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val settings: MusicSettings,
    val state: String = "queued", // queued, planning, generating, synthesizing, decoding, completed, failed
    val progress: Float = 0f,
    val stageDescription: String = "等待就绪",
    val liveLogs: List<String> = emptyList(),
    val abcScore: String? = null,
    val resultAudioPath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("settings", settings.toJson())
        put("state", state)
        put("progress", progress.toDouble())
        put("stageDescription", stageDescription)
        put("liveLogs", JSONArray(liveLogs))
        put("abcScore", abcScore ?: "")
        put("resultAudioPath", resultAudioPath ?: "")
        put("error", error ?: "")
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): MusicJob = MusicJob(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "未命名任务"),
            settings = MusicSettings.fromJson(o.optJSONObject("settings") ?: JSONObject()),
            state = o.optString("state", "queued"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            stageDescription = o.optString("stageDescription", ""),
            liveLogs = runCatching {
                val arr = o.optJSONArray("liveLogs") ?: JSONArray()
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList()),
            abcScore = o.optString("abcScore").takeIf { it.isNotBlank() },
            resultAudioPath = o.optString("resultAudioPath").takeIf { it.isNotBlank() },
            error = o.optString("error").takeIf { it.isNotBlank() },
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
