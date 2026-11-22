package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdCliGenericImageCommandTest {
    private fun options(useOpenCl: Boolean = false) = SdCliGenericImageOptions(
        nativeLib = File("/app/libsd_cli.so"),
        modelFile = File("/models/hub/sdxl-turbo/sd_xl_turbo_1.0_fp16.safetensors"),
        output = File("/outputs/job.part.png"),
        prompt = "a calm mountain lake at sunrise",
        negativePrompt = "blurry, watermark",
        width = 512,
        height = 512,
        steps = 4,
        seed = 42L,
        cfg = 1.0,
        threads = 6,
        useOpenCl = useOpenCl
    )

    @Test
    fun buildsCpuCommandWithOffloadAndMmap() {
        val cmd = buildSdCliGenericImageCommand(options(useOpenCl = false))
        assertEquals("img_gen", cmd.valueAfter("-M"))
        assertEquals("/models/hub/sdxl-turbo/sd_xl_turbo_1.0_fp16.safetensors", cmd.valueAfter("--model"))
        assertEquals("cpu", cmd.valueAfter("--backend"))
        assertTrue(cmd.contains("--offload-to-cpu"))
        assertTrue(cmd.contains("--mmap"))
        assertEquals("4", cmd.valueAfter("--steps"))
        assertEquals("1", cmd.valueAfter("--cfg-scale"))
        assertEquals("512", cmd.valueAfter("-W"))
        assertEquals("512", cmd.valueAfter("-H"))
    }

    @Test
    fun buildsOpenClCommandWhenEnabled() {
        val cmd = buildSdCliGenericImageCommand(options(useOpenCl = true))
        assertEquals("diffusion=GPUOpenCL,te=CPU,vae=CPU", cmd.valueAfter("--backend"))
        assertFalse(cmd.contains("--offload-to-cpu"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDimensionsNotDivisibleBy64() {
        buildSdCliGenericImageCommand(options().copy(width = 500))
    }

    private fun List<String>.valueAfter(flag: String): String {
        val index = indexOf(flag)
        assertTrue("missing $flag", index >= 0 && index + 1 < size)
        return this[index + 1]
    }
}
