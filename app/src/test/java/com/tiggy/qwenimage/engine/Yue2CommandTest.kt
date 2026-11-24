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

        assertEquals("audiocpp-jni", command[0])
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

    @Test
    fun buildsVulkanAndMultiFamilyMusicCommands() {
        val aceCmd = buildAudioCppMusicCommand(
            AudioCppMusicOptions(
                family = AudioCppFamily.AceStep,
                modelPath = File("/models/hub/acestep/ACE-Step-1.5-Turbo-Q8_0.gguf"),
                output = File("/music/ace.part.wav"),
                outputDir = File("/cache/ace"),
                style = "dreamy synthwave",
                bpm = 118,
                lyrics = "[Verse]\nnight drive",
                cot = CoTMode.Melody,
                seed = 7L,
                durationSec = 45,
                odeSteps = 16,
                guidanceScale = 3.0f,
                threads = 6,
                useVulkan = true,
                aceStepDitVariant = "acestep-v15-turbo"
            )
        )
        assertEquals("ace_step", aceCmd.valueAfter("--family"))
        assertEquals("vulkan", aceCmd.valueAfter("--backend"))
        assertEquals("text2music", aceCmd.valueAfter("--task-route"))
        assertEquals("dreamy synthwave", aceCmd.valueAfter("--text"))
        // Turbo caps steps at 8 and ignores diffusion guidance_scale
        assertEquals("8", aceCmd.valueAfter("--num-inference-steps"))
        assertFalse(aceCmd.contains("--guidance-scale"))
        assertTrue("ace_step.dit_model_path=acestep-v15-turbo" in aceCmd.valuesAfter("--load-option"))

        val heartCmd = buildAudioCppMusicCommand(
            AudioCppMusicOptions(
                family = AudioCppFamily.HeartMula,
                modelPath = File("/models/hub/heartmula/HeartMuLa-Q8_0.gguf"),
                output = File("/music/hm.part.wav"),
                outputDir = File("/cache/hm"),
                style = "warm acoustic folk",
                bpm = 90,
                lyrics = "",
                cot = CoTMode.Off,
                seed = 9L,
                durationSec = 30,
                odeSteps = 10,
                guidanceScale = null,
                useVulkan = false
            )
        )
        assertEquals("heartmula", heartCmd.valueAfter("--family"))
        assertEquals("[Instrumental]", heartCmd.valueAfter("--lyrics"))
        assertTrue("tags=warm acoustic folk" in heartCmd.valuesAfter("--request-option"))
        assertTrue("duration_sec=30" in heartCmd.valuesAfter("--request-option"))

        val saCmd = buildAudioCppMusicCommand(
            AudioCppMusicOptions(
                family = AudioCppFamily.StableAudio,
                modelPath = File("/models/hub/sa/Stable-Audio-3-Small-Music-Q8_0.gguf"),
                output = File("/music/sa.part.wav"),
                outputDir = File("/cache/sa"),
                style = "ambient rain piano",
                bpm = 80,
                lyrics = "",
                cot = CoTMode.Off,
                seed = 11L,
                durationSec = 20,
                odeSteps = 8,
                guidanceScale = 1.5f,
                useVulkan = true
            )
        )
        assertEquals("stable_audio", saCmd.valueAfter("--family"))
        assertEquals("vulkan", saCmd.valueAfter("--backend"))
        assertEquals("ambient rain piano", saCmd.valueAfter("--text"))
        assertEquals("20", saCmd.valueAfter("--duration-seconds"))
        assertEquals("1.50", saCmd.valueAfter("--guidance-scale"))
    }

    @Test
    fun parsesAceStepTimingStages() {
        val parser = Yue2ProgressParser()
        val events = parser.feed(
            "[TIMING] ace_step.planner.cot.phase_ms 1200\n" +
                "[TIMING] ace_step.planner.total_ms 3400\n" +
                "[TIMING] ace_step.dit.total_ms 5600\n" +
                "[TIMING] ace_step.vae.decode.total_ms 900\n"
        ) + parser.finish()
        assertEquals(
            listOf(Yue2Stage.Semantic, Yue2Stage.Acoustic, Yue2Stage.Decoding, Yue2Stage.Done),
            events.map { it.stage }
        )
    }

    private fun List<String>.valueAfter(flag: String): String = this[indexOf(flag) + 1]
    private fun List<String>.valuesAfter(flag: String): List<String> =
        indices.filter { this[it] == flag }.map { this[it + 1] }
}
