package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.DecisionDraft
import com.tiggy.qwenimage.data.DecisionAnswer
import com.tiggy.qwenimage.data.DecisionKind
import com.tiggy.qwenimage.data.DecisionOptionResult
import com.tiggy.qwenimage.data.DecisionResult
import com.tiggy.qwenimage.data.LlmCitation
import com.tiggy.qwenimage.data.LlmConversation
import com.tiggy.qwenimage.data.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaExportBridgeTest {

    @Test
    fun parsesStandardWavWithExtraListChunk() {
        val wav = buildSampleWav(sampleRate = 32000, channels = 2, pcmBytes = 640, withListChunk = true)
        val info = ExportFormatters.parseWavHeader(wav)
        assertEquals(1, info.audioFormat)
        assertEquals(2, info.channels)
        assertEquals(32000, info.sampleRate)
        assertEquals(16, info.bitsPerSample)
        assertEquals(640, info.dataSize)
    }

    @Test
    fun formatsConversationMarkdownWithCitations() {
        val conv = LlmConversation(
            id = "c1",
            title = "Wan2.2 参数说明",
            modelId = "qwen3",
            messages = listOf(
                LlmMessage("m1", "user", "为什么要设 vae=CPU？"),
                LlmMessage(
                    id = "m2",
                    role = "assistant",
                    content = "Adreno OpenCL 上的 VAE 解码会产生花屏 [1]。",
                    citations = listOf(LlmCitation("manual.md", 0, "解码阶段必须保持 vae=CPU", 0.91f))
                )
            )
        )
        val md = ExportFormatters.formatConversationMarkdown(conv, "Qwen3-0.6B")
        assertTrue(md.contains("# Wan2.2 参数说明"))
        assertTrue(md.contains("## User"))
        assertTrue(md.contains("## Assistant"))
        assertTrue(md.contains("### Sources"))
        assertTrue(md.contains("[1] manual.md (#1)"))
    }

    @Test
    fun formatsDecisionMarkdownClearly() {
        val q = DecisionDraft(id = "q1", kind = DecisionKind.Noul, instructions = "Should we ship MP4 export?")
        val ans = DecisionAnswer(
            questionId = "q1",
            kind = DecisionKind.Noul,
            options = listOf(
                DecisionOptionResult("Yes", "", 0.92, "A"),
                DecisionOptionResult("No", "", 0.08, "B")
            ),
            bestIndex = 0,
            score = null,
            confidence = 0.84,
            allowedMass = 0.99,
            chunks = 1
        )
        val res = DecisionResult(listOf(ans), prefixTokens = 128, prefixPrefillUs = 50_000, branchPrefillUs = 12_000)
        val md = ExportFormatters.formatDecisionMarkdown("Elephant-1 9B", "Users need WeChat compatibility.", listOf(q), res)
        assertTrue(md.contains("# Decision Analysis"))
        assertTrue(md.contains("Should we ship MP4 export?"))
        assertTrue(md.contains("92.0%"))
    }

    @Test
    fun sanitizesExportFileName() {
        assertEquals("my_report_v1", ExportFormatters.sanitizeExportFileName(" my/report:v1? ", "fallback"))
        assertEquals("fallback", ExportFormatters.sanitizeExportFileName("///...", "fallback"))
    }

    private fun buildSampleWav(sampleRate: Int, channels: Int, pcmBytes: Int, withListChunk: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt chunk
        body.write("fmt ".toByteArray(Charsets.US_ASCII))
        body.write(leInt(16))
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
        }
        body.write(fmt.array())
        if (withListChunk) {
            body.write("LIST".toByteArray(Charsets.US_ASCII))
            body.write(leInt(4))
            body.write("INFO".toByteArray(Charsets.US_ASCII))
        }
        body.write("data".toByteArray(Charsets.US_ASCII))
        body.write(leInt(pcmBytes))
        body.write(ByteArray(pcmBytes))

        val payload = body.toByteArray()
        val full = ByteArrayOutputStream()
        full.write("RIFF".toByteArray(Charsets.US_ASCII))
        full.write(leInt(payload.size))
        full.write(payload)
        return full.toByteArray()
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
