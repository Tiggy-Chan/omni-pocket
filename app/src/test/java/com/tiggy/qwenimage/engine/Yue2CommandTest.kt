package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.CoTMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Yue2CommandTest {
    private fun options(lyrics: String = "[Verse]\nhello", guidance: Float? = null) = Yue2Options(
        nativeLib = File("/app/libyue2_cli.so"),
        modelDir = File("/models/yue2"),
        output = File("/music/song.part.wav"),
        outputDir = File("/cache/yue2-job"),
        style = "pop, piano",
        bpm = 96,
        lyrics = lyrics,
        cot = CoTMode.Melody,
        seed = 42L,
        durationSec = 30,
        odeSteps = 8,
        guidanceScale = guidance
    )

    @Test
    fun passesUserParametersAndBoundsTheSemanticStream() {
        val command = buildYue2Command(options())

        assertEquals("yue2", command.valueAfter("--family"))
        assertEquals("cpu", command.valueAfter("--backend"))
        assertEquals("42", command.valueAfter("--seed"))
        assertEquals("[Verse]\nhello", command.valueAfter("--lyrics"))
        val requests = command.valuesAfter("--request-option")
        assertTrue("style=At 96 BPM, pop, piano" in requests)
        assertTrue("cot=melody" in requests)
        assertTrue("num_inference_steps=8" in requests)
        assertTrue("semantic_max_tokens=750" in requests)
        assertTrue("semantic_min_tokens=562" in requests)
        assertFalse(requests.any { it.startsWith("guidance_scale=") })
        val sessions = command.valuesAfter("--session-option")
        assertTrue("yue2.model_gguf=$YUE2_MODEL_FILE" in sessions)
        assertTrue("yue2.vae_gguf=$YUE2_VAE_FILE" in sessions)
    }

    @Test
    fun blankLyricsBecomeInstrumentalAndGuidanceIsForwarded() {
        val command = buildYue2Command(options(lyrics = " ", guidance = 1.5f))

        assertEquals("[Instrumental]", command.valueAfter("--lyrics"))
        assertTrue("guidance_scale=1.5" in command.valuesAfter("--request-option"))
    }

    @Test
    fun rejectsOutOfRangeRequests() {
        assertThrows(IllegalArgumentException::class.java) { buildYue2Command(options().copy(durationSec = 5)) }
        assertThrows(IllegalArgumentException::class.java) { buildYue2Command(options().copy(style = "")) }
        assertThrows(IllegalArgumentException::class.java) { buildYue2Command(options().copy(output = File("/music/a.mp3"))) }
    }

    @Test
    fun parsesStageTimingsFromSplitChunks() {
        val parser = Yue2ProgressParser()
        val events = parser.feed("[TRACE ts=1] yue2.perf_mode off\n[TIMING ts=1] yue2.ar.init_ms 1756.9\n[TIM") +
            parser.feed("ING ts=2] yue2.semantic.tokens 150\n[TIMING ts=3] yue2.nar_ms 14625.1\n") +
            parser.feed("[TIMING ts=4] yue2.vae_decode_ms 1967.1") +
            parser.finish()

        assertEquals(listOf(Yue2Stage.Planning, Yue2Stage.Acoustic, Yue2Stage.Decoding, Yue2Stage.Done), events.map { it.stage })
        assertEquals(150, events[1].tokens)
        assertEquals(14.6251, events[2].seconds!!, 1e-4)
    }

    private fun List<String>.valueAfter(flag: String): String = this[indexOf(flag) + 1]
    private fun List<String>.valuesAfter(flag: String): List<String> =
        indices.filter { this[it] == flag }.map { this[it + 1] }
}
