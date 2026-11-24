package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdCliProgressParserTest {
    private fun realLog(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sdcli_wan_run.log")) { "fixture missing" }
            .bufferedReader().readText()

    @Test
    fun parsesAFullRealRunInOrder() {
        val parser = SdCliProgressParser()
        val events = parser.feed(realLog()) + parser.finish()

        val steps = events.filter { it.step > 0 }
        assertEquals(listOf(1, 2, 3, 4), steps.map { it.step })
        assertTrue(steps.all { it.totalSteps == 4 })
        assertEquals(92.97f, steps.first().secondsPerStep!!, 0.001f)

        val stages = events.map { it.stage }.distinct()
        assertEquals(
            listOf(
                SdCliStage.Loading, SdCliStage.TextEncoding, SdCliStage.Denoising,
                SdCliStage.Decoding, SdCliStage.Saving
            ),
            stages
        )

        val textEncoder = events.first { it.finishedSeconds != null && it.stage == SdCliStage.TextEncoding }
        assertEquals(81.43f, textEncoder.finishedSeconds!!, 0.001f)
        val done = events.last { it.completed }
        assertEquals(479.29f, done.finishedSeconds!!, 0.001f)
    }

    @Test
    fun ignoresWeightLoadingBars() {
        val parser = SdCliProgressParser()
        val events = parser.feed("  |###########      | 51/242 - 1.66GB/s\u001B[K\r  |#####| 99/242 - 1.78MB/s\r")
        assertTrue(events.isEmpty())
    }

    @Test
    fun repeatedRedrawsOfTheSameStepCountOnce() {
        val parser = SdCliProgressParser()
        parser.feed("sampling using Euler method\n")
        val bar = "  |====>      | 2/4 - 81.80s/it\u001B[K\r"
        val events = parser.feed(bar + bar + bar)
        assertEquals(1, events.size)
        assertEquals(2, events.single().step)
    }

    @Test
    fun handlesSegmentsSplitAcrossChunks() {
        val parser = SdCliProgressParser()
        val first = parser.feed("[INFO   ] video.cpp:1712 - sampling comp")
        assertTrue(first.isEmpty())
        val second = parser.feed("leted, taking 340.44s\n")
        assertEquals(1, second.size)
        assertEquals(SdCliStage.Decoding, second.single().stage)
        assertEquals(340.44f, second.single().finishedSeconds!!, 0.001f)
    }

    @Test
    fun convertsItPerSecondToSecondsPerStep() {
        val parser = SdCliProgressParser()
        val event = parser.feed("sampling using Euler method\n  |==| 1/8 - 2.00it/s\r").single()
        assertNotNull(event.secondsPerStep)
        assertEquals(0.5f, event.secondsPerStep!!, 0.0001f)
    }

    @Test
    fun ignoresVaeTilingAfterSamplingCompletes() {
        val parser = SdCliProgressParser()
        parser.feed("sampling using Euler method\n  |==| 4/4 - 81.80s/it\r")
        parser.feed("sampling completed, taking 340.44s\n")
        val events = parser.feed("  |==| 1/10 - 2.00s/it\r  |==| 2/10 - 2.00s/it\r")
        assertTrue(events.isEmpty())
    }

    @Test
    fun benignGgufWarningIsNotAnEvent() {
        val parser = SdCliProgressParser()
        val events = parser.feed("[ERROR  ] ggml - gguf_init_from_reader: tensor 'patch_embedding.weight' has invalid number of dimensions: 5 > 4\n")
        assertTrue(events.isEmpty())
    }

    @Test
    fun parsesRealMiniMaxH3PiFlowRunAndIgnoresVaeTiles() {
        val log = """
            [I] loading diffusion model from '/models/minimax_h3_fl2va_pruned-Q2_K.gguf' --- diffusion_engine.cpp:676
            [I] loading llm from '/models/qwen3vl-32B-MiniMax-H3-Q2_K.gguf' --- diffusion_engine.cpp:733
            [I] loading vae from '/models/minimax_h3_video_vae_int8_convrot.safetensors' --- diffusion_engine.cpp:747
            [I] loading audio VAE from '/models/minimax_h3_audio_vae_fp32.safetensors' --- diffusion_engine.cpp:771
            [W] vision weights appear reshaped/incompatible for quantization (attn.proj ne0=256 vs hidden=1152, fc1 bias=4304 vs intermediate=19368), vision disabled --- llm.hpp:477
            [I] apply_loras completed, taking 0.02s --- diffusion_engine.cpp:1820
            [I] get_sigmas with pi-Flow scheduler --- denoiser.hpp:1265
            [I] sampling using Euler method --- request.cpp:414
            [I] get_learned_condition completed, taking 22.81s --- video.cpp:1258
              |=========================>                        | 1/2 -  33.10s/it
              |==================================================| 2/2 -  33.29s/it
            [I] sampling completed, taking 66.39s --- video.cpp:1760
            [I] MiniMax-H3 audio VAE decode completed, taking 4.43s --- minimax_h3_audio_vae.hpp:495
              |============>                                     | 1/4 -  62.51s/it - ETA 03:07
              |==================================================| 4/4 -  57.54s/it
            [I] decode_first_stage completed, taking 238.27s --- video.cpp:1289
            [I] (357 / 357) LoRA tensors have been applied, lora_file_path = /models/pi_h3_2step_adapter.safetensors --- lora.hpp:1101
            [I] generate_video completed in 331.98s --- video.cpp:1992
        """.trimIndent() + "\n"

        val parser = SdCliProgressParser()
        val events = parser.feed(log) + parser.finish()

        assertEquals(4, events.count { it.stage == SdCliStage.Loading })
        val textEncoder = events.first { it.stage == SdCliStage.TextEncoding }
        assertEquals(22.81f, textEncoder.finishedSeconds!!, 0.001f)

        val steps = events.filter { it.step > 0 }
        assertEquals(listOf(1, 2), steps.map { it.step })
        assertTrue(steps.all { it.totalSteps == 2 })

        val decoding = events.first { it.stage == SdCliStage.Decoding }
        assertEquals(66.39f, decoding.finishedSeconds!!, 0.001f)

        val done = events.last { it.completed }
        assertEquals(331.98f, done.finishedSeconds!!, 0.001f)
    }
}
