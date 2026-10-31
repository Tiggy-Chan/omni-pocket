package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.CoTMode
import java.io.File

/** YuE2 writes 25 semantic frames per second of audio. */
internal const val YUE2_FRAMES_PER_SECOND = 25

internal data class Yue2Options(
    val nativeLib: File,
    val modelDir: File,
    val output: File,
    val outputDir: File,
    val style: String,
    val bpm: Int,
    val lyrics: String,
    val cot: CoTMode,
    val seed: Long,
    val durationSec: Int,
    val odeSteps: Int,
    val guidanceScale: Float?,
    val threads: Int = 6
)

internal fun buildYue2Command(options: Yue2Options): List<String> {
    require(options.style.isNotBlank()) { "音乐风格不能为空" }
    require(options.durationSec in 10..240) { "时长必须为 10–240 秒" }
    require(options.odeSteps in 4..64) { "ODE 步数必须为 4–64" }
    require(options.seed >= 0) { "seed 不能为负数" }
    require(options.bpm in 50..220) { "BPM 必须为 50–220" }
    require(options.threads in 2..8)
    require(options.output.extension.equals("wav", ignoreCase = true))
    options.guidanceScale?.let { require(it.isFinite() && it in 0f..20f) { "引导强度必须在 0 到 20 之间" } }

    // The target length bounds the semantic stream; the model may stop earlier at its own end token.
    val maxTokens = options.durationSec * YUE2_FRAMES_PER_SECOND
    val minTokens = (maxTokens * 3 / 4).coerceAtMost(maxTokens)
    return buildList {
        add(options.nativeLib.absolutePath)
        addAll(listOf("--task", "gen", "--family", "yue2", "--backend", "cpu"))
        addAll(listOf("--model", options.modelDir.absolutePath))
        addAll(listOf("--threads", options.threads.toString()))
        addAll(listOf("--session-option", "yue2.model_gguf=$YUE2_MODEL_FILE"))
        addAll(listOf("--session-option", "yue2.vae_gguf=$YUE2_VAE_FILE"))
        addAll(listOf("--lyrics", options.lyrics.ifBlank { "[Instrumental]" }))
        // YuE2 has no tempo parameter; its style prompt carries tempo the same way the upstream examples do.
        addAll(listOf("--request-option", "style=At ${options.bpm} BPM, ${options.style.trim()}"))
        addAll(listOf("--request-option", "cot=${options.cot.tag}"))
        addAll(listOf("--request-option", "num_inference_steps=${options.odeSteps}"))
        addAll(listOf("--request-option", "semantic_min_tokens=$minTokens"))
        addAll(listOf("--request-option", "semantic_max_tokens=$maxTokens"))
        options.guidanceScale?.let { addAll(listOf("--request-option", "guidance_scale=$it")) }
        addAll(listOf("--seed", options.seed.toString()))
        addAll(listOf("--out", options.output.absolutePath))
        addAll(listOf("--out-dir", options.outputDir.absolutePath))
        add("--log")
    }
}

internal const val YUE2_MODEL_FILE = "yue2-3b-q4_0.gguf"
internal const val YUE2_VAE_FILE = "yue2-vae-f16.gguf"

internal enum class Yue2Stage { Loading, Planning, Semantic, Acoustic, Decoding, Done }

internal data class Yue2Event(val stage: Yue2Stage, val seconds: Double? = null, val tokens: Int? = null)

/**
 * Turns audio.cpp `--log` timing lines into stage events. The CLI reports each stage once it
 * finishes, so progress advances per stage rather than per token.
 */
internal class Yue2ProgressParser {
    private var pending = ""

    fun feed(chunk: String): List<Yue2Event> {
        val text = pending + chunk
        val lines = text.split('\n')
        pending = lines.last()
        return lines.dropLast(1).mapNotNull(::parseLine)
    }

    fun finish(): List<Yue2Event> = parseLine(pending).let { pending = ""; listOfNotNull(it) }

    private fun parseLine(raw: String): Yue2Event? {
        val line = raw.trim()
        val match = TIMING.find(line) ?: return null
        val key = match.groupValues[1]
        val value = match.groupValues[2].toDoubleOrNull() ?: return null
        return when (key) {
            "yue2.ar.init_ms" -> Yue2Event(Yue2Stage.Planning, value / 1000)
            "yue2.plan_ms" -> Yue2Event(Yue2Stage.Semantic, value / 1000)
            "yue2.semantic.tokens" -> Yue2Event(Yue2Stage.Acoustic, tokens = value.toInt())
            "yue2.nar_ms" -> Yue2Event(Yue2Stage.Decoding, value / 1000)
            "yue2.vae_decode_ms" -> Yue2Event(Yue2Stage.Done, value / 1000)
            else -> null
        }
    }

    private companion object {
        val TIMING = Regex("""^\[TIMING[^\]]*\]\s+(\S+)\s+(\S+)""")
    }
}
