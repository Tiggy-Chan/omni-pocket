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
}
