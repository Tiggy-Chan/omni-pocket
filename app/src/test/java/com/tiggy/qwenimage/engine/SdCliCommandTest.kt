package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdCliCommandTest {
    private fun options(frames: Int = 25) = SdCliVideoOptions(
        nativeLib = File("/app/libsd_cli.so"),
        diffusionModel = File("/models/wan.gguf"),
        textEncoder = File("/models/t5.gguf"),
        vae = File("/models/tae.safetensors"),
        output = File("/outputs/result.part.webm"),
        prompt = "a red apple",
        width = 480,
        height = 832,
        frames = frames,
        fps = 16,
        steps = 4,
        seed = 123456789L,
        shift = 5f
    )

    @Test
    fun passesEveryUserVisibleParameterToTheCli() {
        val command = buildSdCliVideoCommand(options())

        assertEquals("16", command.valueAfter("--fps"))
        assertEquals("123456789", command.valueAfter("--seed"))
        assertEquals("25", command.valueAfter("--video-frames"))
        assertEquals("4", command.valueAfter("--steps"))
        assertEquals("6", command.valueAfter("-t"))
        assertEquals("480", command.valueAfter("-W"))
        assertEquals("832", command.valueAfter("-H"))
        assertTrue(command.contains("--diffusion-fa"))
    }

    @Test
    fun addsFirstFrameOnlyWhenProvided() {
        val withoutImage = buildSdCliVideoCommand(options())
        val withImage = buildSdCliVideoCommand(options().copy(initImage = File("/cache/first.png")))

        assertFalse(withoutImage.contains("-i"))
        assertEquals("/cache/first.png", withImage.valueAfter("-i"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFramesAboveTheOnDeviceLimitInsteadOfClamping() {
        buildSdCliVideoCommand(options(frames = 81))
    }

    private fun List<String>.valueAfter(flag: String): String {
        val index = indexOf(flag)
        assertTrue("missing $flag", index >= 0 && index + 1 < size)
        return this[index + 1]
    }
}
