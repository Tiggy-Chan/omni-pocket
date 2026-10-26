package com.tiggy.qwenimage.engine

/** Coarse phases of a stable-diffusion.cpp image or video run, in the order the CLI reports them. */
enum class SdCliStage { Loading, TextEncoding, Denoising, Decoding, Saving }

/** One thing learned from the CLI output. Only fields that were actually printed are set. */
data class SdCliEvent(
    val stage: SdCliStage,
    val step: Int = 0,
    val totalSteps: Int = 0,
    /** Seconds per sampling step as printed by the CLI (already converted from it/s when needed). */
    val secondsPerStep: Float? = null,
    /** Duration the CLI reported for the phase that just finished. */
    val finishedSeconds: Float? = null,
    /** True once the CLI reports the whole generation as completed. */
    val completed: Boolean = false
)

/**
 * Incremental parser for `libsd_cli` output. The CLI redraws its progress bar with carriage returns, so
 * input is split on both `\r` and `\n` and a trailing partial segment is kept until more text arrives.
 *
 * It only reports what the CLI really printed; nothing is inferred from timers.
 */
class SdCliProgressParser {
    private val pending = StringBuilder()
    private var lastStep = 0
    private var samplingStarted = false
    private var samplingFinished = false

    fun feed(chunk: String): List<SdCliEvent> {
        pending.append(chunk)
        val events = ArrayList<SdCliEvent>()
        var start = 0
        for (i in pending.indices) {
            val c = pending[i]
            if (c == '\r' || c == '\n') {
                parse(pending.substring(start, i))?.let(events::add)
                start = i + 1
            }
        }
        pending.delete(0, start)
        return events
    }

    fun finish(): List<SdCliEvent> {
        val tail = pending.toString()
        pending.setLength(0)
        return listOfNotNull(parse(tail))
    }

    private fun parse(raw: String): SdCliEvent? {
        val line = raw.replace(ANSI, "").trim()
        if (line.isEmpty()) return null

        if (line.contains("sampling using ")) {
            samplingStarted = true
            return null
        }
        STEP.find(line)?.let { m ->
            if (!samplingStarted || samplingFinished) return null
            val step = m.groupValues[1].toInt()
            val total = m.groupValues[2].toInt()
            if (total <= 0 || step > total) return null
            val rate = m.groupValues[3].toFloatOrNull() ?: return null
            val secondsPerStep = if (m.groupValues[4] == "it/s") (if (rate > 0f) 1f / rate else null) else rate
            if (step == lastStep) return null
            lastStep = step
            return SdCliEvent(SdCliStage.Denoising, step, total, secondsPerStep)
        }
        if (line.contains("get_learned_condition completed")) {
            return SdCliEvent(SdCliStage.TextEncoding, finishedSeconds = seconds(line))
        }
        if (line.contains("sampling completed")) {
            samplingFinished = true
            return SdCliEvent(SdCliStage.Decoding, finishedSeconds = seconds(line))
        }
        if (line.contains("decode_first_stage completed")) {
            return SdCliEvent(SdCliStage.Saving, finishedSeconds = seconds(line))
        }
        if (line.contains("generate_video completed") || line.contains("generate_image completed")) {
            return SdCliEvent(SdCliStage.Saving, finishedSeconds = seconds(line), completed = true)
        }
        if (line.contains("loading diffusion model") || line.contains("loading t5xxl") || line.contains("loading tae") ||
            line.contains("loading llm") || line.contains("loading vae")
        ) {
            return SdCliEvent(SdCliStage.Loading)
        }
        return null
    }

    private fun seconds(line: String): Float? = SECONDS.find(line)?.groupValues?.get(1)?.toFloatOrNull()

    private companion object {
        val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val STEP = Regex("""\|\s*(\d+)/(\d+)\s+-\s+([0-9.]+)(s/it|it/s)""")
        val SECONDS = Regex("""(?:taking|in)\s+([0-9.]+)s""")
    }
}
