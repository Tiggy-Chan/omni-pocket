package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InferenceWorkerProtocolTest {
    @Test
    fun decodesSplitMultiByteUtf8SequencesWithoutCorruption() {
        val original = "去噪 3/4 · 速度 1.25s/步 🚀 完成！"
        val bytes = original.toByteArray(Charsets.UTF_8)
        val decoder = Utf8StreamDecoder()

        val out = StringBuilder()
        // Feed 2 bytes at a time to force splits across 3-byte CJK and 4-byte emoji codepoints.
        var i = 0
        while (i < bytes.size) {
            val end = (i + 2).coerceAtMost(bytes.size)
            out.append(decoder.decode(bytes.copyOfRange(i, end)))
            i = end
        }
        out.append(decoder.finish())

        assertEquals(original, out.toString())
    }

    @Test
    fun replacesMalformedTrailingBytesOnFinish() {
        val decoder = Utf8StreamDecoder()
        val partial = byteArrayOf(0xE4.toByte(), 0xB8.toByte()) // truncated 3-byte CJK sequence
        assertEquals("", decoder.decode(partial))
        assertEquals("\uFFFD", decoder.finish())
    }

    @Test
    fun definesDistinctProtocolCodesAndEngineNames() {
        val messageIds = setOf(
            InferenceWorkerProtocol.MSG_RUN,
            InferenceWorkerProtocol.MSG_CANCEL,
            InferenceWorkerProtocol.MSG_OUTPUT,
            InferenceWorkerProtocol.MSG_COMPLETED,
            InferenceWorkerProtocol.MSG_FAILED
        )
        assertEquals(5, messageIds.size)
        assertNotEquals(InferenceWorkerProtocol.ENGINE_SD, InferenceWorkerProtocol.ENGINE_YUE2)
    }
}
