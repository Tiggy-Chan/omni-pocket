package com.tiggy.qwenimage.data

import org.json.JSONObject

data class MossLocalSettings(
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Double = 3.0,
    val threads: Int = 6
) {
    fun validate() {
        require(steps in 1..100) { "本机 MOSS 步数必须为 1–100" }
        require(cfg.isFinite() && cfg in 0.0..20.0) { "本机 CFG 必须位于 0 到 20" }
        require(threads in 2..8) { "本机 MOSS 线程数必须为 2–8" }
    }

    fun toJson() = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("steps", steps)
        put("cfg", cfg)
        put("threads", threads)
    }

    companion object {
        fun fromJson(o: JSONObject?) = MossLocalSettings(
            width = o?.optInt("width", 512) ?: 512,
            height = o?.optInt("height", 512) ?: 512,
            steps = o?.optInt("steps", 20) ?: 20,
            cfg = o?.optDouble("cfg", 3.0) ?: 3.0,
            threads = o?.optInt("threads", 6) ?: 6
        )
    }
}

val ImageGenerationBackend.isMoss: Boolean
    get() = this != ImageGenerationBackend.QwenLocal

fun ImageGenerationBackend.displayName(): String = when (this) {
    ImageGenerationBackend.QwenLocal -> "Qwen · 本机"
    ImageGenerationBackend.MossLocal -> "MOSS · 本机"
}
