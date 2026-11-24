package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.DecisionDraft
import com.tiggy.qwenimage.data.DecisionAnswer
import com.tiggy.qwenimage.data.DecisionKind
import com.tiggy.qwenimage.data.DecisionResult
import com.tiggy.qwenimage.data.LlmConversation
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class WavHeaderInfo(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
    val dataSize: Int
)

/**
 * Pure-Kotlin export helpers (WAV RIFF header parsing, Markdown document formatters,
 * and safe filename sanitization) shared by [MediaExportBridge] and JVM unit tests.
 */
object ExportFormatters {
    fun sanitizeExportFileName(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.')
            .take(48)
        return cleaned.ifBlank { fallback }
    }

    /**
     * Parses a standard RIFF/WAVE header and locates the PCM `data` chunk.
     * Supports extra chunks (LIST/INFO/fact/JUNK) before `data`.
     */
    fun parseWavHeader(bytes: ByteArray): WavHeaderInfo {
        require(bytes.size >= 44) { "WAV file is too short" }
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "Not a valid RIFF/WAVE file" }

        var offset = 12
        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val payloadStart = offset + 8
            if (chunkSize < 0) break
            when (chunkId) {
                "fmt " -> {
                    require(payloadStart + 16 <= bytes.size) { "Truncated WAV fmt chunk" }
                    val bb = ByteBuffer.wrap(bytes, payloadStart, 16).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                }
                "data" -> {
                    require(audioFormat == 1 && bitsPerSample == 16 && channels in 1..2 && sampleRate in 8000..96000) {
                        "Unsupported WAV PCM format (fmt=$audioFormat, bits=$bitsPerSample, ch=$channels, sr=$sampleRate)"
                    }
                    val actualSize = chunkSize.coerceAtMost(bytes.size - payloadStart).coerceAtLeast(0)
                    return WavHeaderInfo(
                        audioFormat = audioFormat,
                        channels = channels,
                        sampleRate = sampleRate,
                        bitsPerSample = bitsPerSample,
                        dataOffset = payloadStart,
                        dataSize = actualSize
                    )
                }
            }
            val paddedSize = chunkSize + (chunkSize and 1)
            offset = payloadStart + paddedSize
        }
        throw IllegalArgumentException("WAV file missing PCM data chunk")
    }

    /** Stream-reads just enough header bytes (up to 64 KB) to parse a WAV header without loading full audio into RAM. */
    fun parseWavHeader(input: InputStream): WavHeaderInfo {
        val probe = ByteArray(64 * 1024)
        var read = 0
        while (read < probe.size) {
            val n = input.read(probe, read, probe.size - read)
            if (n <= 0) break
            read += n
        }
        val slice = if (read == probe.size) probe else probe.copyOf(read)
        val riff = if (slice.size >= 4) String(slice, 0, 4, Charsets.US_ASCII) else ""
        val wave = if (slice.size >= 12) String(slice, 8, 4, Charsets.US_ASCII) else ""
        require(riff == "RIFF" && wave == "WAVE") { "Not a valid RIFF/WAVE file" }

        var offset = 12
        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        while (offset + 8 <= slice.size) {
            val chunkId = String(slice, offset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(slice, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val payloadStart = offset + 8
            if (chunkSize < 0) break
            when (chunkId) {
                "fmt " -> {
                    require(payloadStart + 16 <= slice.size) { "Truncated WAV fmt chunk" }
                    val bb = ByteBuffer.wrap(slice, payloadStart, 16).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int
                    bb.short
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                }
                "data" -> {
                    require(audioFormat == 1 && bitsPerSample == 16 && channels in 1..2 && sampleRate in 8000..96000) {
                        "Unsupported WAV PCM format (fmt=$audioFormat, bits=$bitsPerSample, ch=$channels, sr=$sampleRate)"
                    }
                    return WavHeaderInfo(audioFormat, channels, sampleRate, bitsPerSample, payloadStart, chunkSize)
                }
            }
            offset = payloadStart + chunkSize + (chunkSize and 1)
        }
        throw IllegalArgumentException("WAV file missing PCM data chunk")
    }

    fun formatConversationMarkdown(conversation: LlmConversation, modelName: String): String = buildString {
        append("# ").append(conversation.title.ifBlank { "Omni Pocket Chat" }).append("\n\n")
        append("- Model: ").append(modelName).append('\n')
        append("- Messages: ").append(conversation.messages.size).append("\n\n---\n\n")
        conversation.messages.forEach { msg ->
            if (msg.role == "user") {
                append("## User\n\n").append(msg.content.trim()).append("\n\n")
            } else {
                append("## Assistant\n\n")
                if (msg.reasoning.isNotBlank()) {
                    append("> Thinking:\n")
                    msg.reasoning.trim().lineSequence().forEach { line ->
                        append("> ").append(line).append('\n')
                    }
                    append('\n')
                }
                append(msg.content.trim()).append("\n\n")
                if (msg.citations.isNotEmpty()) {
                    append("### Sources\n\n")
                    msg.citations.forEachIndexed { idx, cite ->
                        append("- [").append(idx + 1).append("] ").append(cite.docName)
                            .append(" (#").append(cite.chunkIndex + 1).append("): ")
                            .append(cite.snippet.replace('\n', ' ').take(160)).append('\n')
                    }
                    append('\n')
                }
            }
        }
    }.trimEnd() + "\n"

    fun formatDecisionMarkdown(
        modelName: String,
        stateText: String,
        questions: List<DecisionDraft>,
        result: DecisionResult
    ): String = buildString {
        append("# Decision Analysis\n\n")
        append("- Model: ").append(modelName).append('\n')
        append("- Shared prefix tokens: ").append(result.prefixTokens).append("\n\n")
        append("## Context / State\n\n")
        append(stateText.trim()).append("\n\n")
        append("## Results\n\n")
        questions.forEachIndexed { idx, q ->
            val answer = result.answers.firstOrNull { it.questionId == q.id }
            append("### Q").append(idx + 1).append(": ").append(q.instructions.trim().ifBlank { "Question ${idx + 1}" }).append("\n\n")
            if (answer != null) {
                appendDecisionAnswer(answer)
            }
        }
    }.trimEnd() + "\n"

    private fun StringBuilder.appendDecisionAnswer(answer: DecisionAnswer) {
        val best = answer.options.getOrNull(answer.bestIndex)
        when (answer.kind) {
            DecisionKind.Noul -> {
                val label = if (answer.bestIndex == 0) "Yes" else "No"
                val pct = String.format(Locale.US, "%.1f%%", (best?.probability ?: 0.0) * 100.0)
                append("**Conclusion**: ").append(label).append(" (").append(pct).append(")\n\n")
            }
            DecisionKind.Choice -> {
                val pct = String.format(Locale.US, "%.1f%%", (best?.probability ?: 0.0) * 100.0)
                append("**Recommended**: ").append(best?.name.orEmpty()).append(" (").append(pct).append(")\n\n")
            }
            DecisionKind.Score -> {
                val scoreStr = String.format(Locale.US, "%.2f / %d", answer.score ?: 0.0, answer.options.lastIndex)
                append("**Score**: ").append(scoreStr).append("\n\n")
            }
        }
        answer.options.forEachIndexed { i, opt ->
            val optName = when (answer.kind) {
                DecisionKind.Noul -> if (i == 0) "Yes" else "No"
                DecisionKind.Choice -> opt.name
                DecisionKind.Score -> "Level $i"
            }
            append("- ").append(optName).append(": ")
                .append(String.format(Locale.US, "%.1f%%", opt.probability * 100.0)).append('\n')
        }
        append('\n')
    }
}
