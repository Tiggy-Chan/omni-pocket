package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdCliGenericImageCommandTest {
    private fun options(useOpenCl: Boolean = false) = SdCliGenericImageOptions(
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
        assertEquals("sd-jni", cmd[0])
        assertFalse(cmd.contains("/system/bin/nice"))
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
        assertTrue(cmd.contains("--diffusion-fa"))
        assertTrue(cmd.contains("--vae-tiling"))
        assertFalse(cmd.contains("--offload-to-cpu"))
    }

    @Test
    fun buildsModularZImageCommandWithCustomSampler() {
        val cmd = buildSdCliGenericImageCommand(
            options(useOpenCl = true).copy(
                modelFile = File("/models/hub/z-image/z-image-turbo-Q4_0.gguf"),
                llmFile = File("/models/hub/_companions/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"),
                vaeFile = File("/models/hub/_companions/flux1_ae.safetensors"),
                sampler = "dpm++2m",
                vaeTiling = false
            )
        )
        assertFalse(cmd.contains("--model"))
        assertEquals("/models/hub/z-image/z-image-turbo-Q4_0.gguf", cmd.valueAfter("--diffusion-model"))
        assertEquals("/models/hub/_companions/Qwen3-4B-Instruct-2507-Q4_K_M.gguf", cmd.valueAfter("--llm"))
        assertEquals("/models/hub/_companions/flux1_ae.safetensors", cmd.valueAfter("--vae"))
        assertEquals("dpm++2m", cmd.valueAfter("--sampling-method"))
        assertFalse(cmd.contains("--vae-tiling"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSampler() {
        buildSdCliGenericImageCommand(options().copy(sampler = "invalid_sampler"))
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
