package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufHeaderParserTest {
    private fun writeGguf(
        version: Int = 3,
        tensorCount: Long = 120L,
        kvs: List<Pair<String, Any>>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun intLE(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun longLE(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            longLE(b.size.toLong())
            out.write(b)
        }
        out.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
        intLE(version)
        longLE(tensorCount)
        longLE(kvs.size.toLong())
        for ((k, v) in kvs) {
            str(k)
            when (v) {
                is String -> {
                    intLE(8) // STRING
                    str(v)
                }
                is Int -> {
                    intLE(4) // UINT32
                    intLE(v)
                }
                is List<*> -> {
                    intLE(9) // ARRAY
                    intLE(8) // STRING elements
                    longLE(v.size.toLong())
                    v.forEach { str(it.toString()) }
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun parsesGgufV3HeaderSkippingLargeTokenizerArray() {
        val bytes = writeGguf(
            kvs = listOf(
                "tokenizer.ggml.tokens" to listOf("a", "b", "c", "hello", "world"),
                "general.architecture" to "qwen35",
                "general.name" to "Qwen3.5-4B-Instruct",
                "general.file_type" to 15 // Q4_K_M
            )
        )
        val info = GgufHeaderParser.parse(ByteArrayInputStream(bytes))
        assertNotNull(info)
        assertEquals(3, info!!.version)
        assertEquals("qwen35", info.architecture)
        assertEquals("Qwen3.5-4B-Instruct", info.modelName)
        assertEquals("Q4_K_M", info.fileTypeQuantTag)

        val classified = GgufHeaderParser.classifyFile("my-local-model.gguf", info)
        assertEquals(HubModelType.GgufChat, classified.suggestedType)
        assertEquals(LocalImportConfidence.High, classified.confidence)
        assertEquals("Q4_K_M", classified.quantTag)
    }

    @Test
    fun classifiesElephantDecisionAndMiniMaxH3AndFlux3AndCompanions() {
        val decisionInfo = GgufHeaderInfo(3, 200, 10, architecture = "qwen3", modelName = "elephant-1-9b", fileType = 15, fileTypeQuantTag = "Q4_K_M")
        val decision = GgufHeaderParser.classifyFile("elephant-1-Q4_K_M.gguf", decisionInfo)
        assertEquals(HubModelType.DecisionGguf, decision.suggestedType)

        val h3 = GgufHeaderParser.classifyFile(
            "minimax_h3_fl2va_pruned-Q2_K.gguf",
            GgufHeaderInfo(3, 532, 0)
        )
        assertEquals(HubModelType.VideoWan, h3.suggestedType)
        assertEquals("minimax_h3", h3.metadata["arch"])
        assertEquals("Q2_K", h3.quantTag)
        assertEquals(5, h3.expectedCompanionKeys.size)

        val flux3 = GgufHeaderParser.classifyFile("flux-3-image-klein-UD-Q4_K_XL.gguf", GgufHeaderInfo(3, 400, 0))
        assertEquals(HubModelType.SdImage, flux3.suggestedType)
        assertEquals("flux3_klein", flux3.metadata["arch"])
        assertEquals("UD-Q4_K_XL", flux3.quantTag)
        assertEquals("true", flux3.metadata["supportsEdit"])

        val piAdapter = GgufHeaderParser.classifyFile("pi_h3_2step_adapter.safetensors")
        assertEquals("pi_h3_2step_adapter.safetensors", piAdapter.companionSharedKey)

        val sdxl = GgufHeaderParser.classifyFile("juggernautXL_v9.safetensors")
        assertEquals(HubModelType.SdImage, sdxl.suggestedType)
        assertNull(sdxl.companionSharedKey)
    }

    @Test
    fun rejectsNonGgufMagic() {
        val garbage = "not a gguf file".toByteArray()
        assertNull(GgufHeaderParser.parse(ByteArrayInputStream(garbage)))
    }
}
