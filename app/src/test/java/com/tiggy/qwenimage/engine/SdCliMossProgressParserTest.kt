package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdCliMossProgressParserTest {
    @Test
    fun recognizesMossImageCompletionAndLoadingStages() {
        val parser = SdCliProgressParser()
        val log = buildString {
            append("[INFO] loading diffusion model from 'moss.gguf'\n")
            append("[INFO] loading llm from 'qwen.gguf'\n")
            append("[INFO] loading vae from 'flux2.safetensors'\n")
            append("[INFO] get_learned_condition completed, taking 30.25s\n")
            append("sampling using Euler method\n")
            append("  |==| 1/2 - 10.00s/it\r")
            append("  |====| 2/2 - 10.00s/it\r")
            append("sampling completed, taking 20.00s\n")
            append("decode_first_stage completed, taking 2.00s\n")
            append("generate_image completed in 52.25s\n")
        }

        val events = parser.feed(log) + parser.finish()

        assertTrue(events.any { it.stage == SdCliStage.Loading })
        assertTrue(events.any { it.stage == SdCliStage.TextEncoding })
        assertEquals(listOf(1, 2), events.filter { it.stage == SdCliStage.Denoising }.map { it.step })
        val completed = events.last { it.completed }
        assertEquals(SdCliStage.Saving, completed.stage)
        assertEquals(52.25f, completed.finishedSeconds!!, 0.001f)
    }

    @Test
    fun imageCompletionDoesNotInventSamplingSteps() {
        val parser = SdCliProgressParser()
        val events = parser.feed("generate_image completed in 1.00s\n") + parser.finish()

        assertEquals(1, events.size)
        assertTrue(events.single().completed)
        assertEquals(0, events.single().step)
    }
}
