package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class SdCliMossCommandTest {
    private fun options() = SdCliMossOptions(
        nativeLib = File("/app/libmoss_cli.so"),
        diffusionModel = File("/models/MOSS-Image-SFT-Q4_0.gguf"),
        textEncoder = File("/models/Qwen3.5-9B-Q4_K_M.gguf"),
        vae = File("/models/flux2-vae_small.safetensors"),
        tokenizer = File("/models/tokenizer.json"),
        output = File("/outputs/result.part.png"),
        prompt = "一张写着 \"你好，MOSS\" 的海报",
        negativePrompt = "watermark",
        width = 512,
        height = 512,
        steps = 20,
        seed = 4_294_967_296L,
        cfg = 3.0,
        shift = 5.0,
        threads = 6
    )

    @Test
    fun passesTheRealLocalModelsAndEverySamplingParameter() {
        val command = buildSdCliMossCommand(options())

        assertEquals("/app/libmoss_cli.so", command[3])
        assertEquals("img_gen", command.valueAfter("-M"))
        assertEquals("/models/MOSS-Image-SFT-Q4_0.gguf", command.valueAfter("--diffusion-model"))
        assertEquals("/models/Qwen3.5-9B-Q4_K_M.gguf", command.valueAfter("--llm"))
        assertEquals("/models/flux2-vae_small.safetensors", command.valueAfter("--vae"))
        assertEquals("flux2", command.valueAfter("--vae-format"))
        assertEquals("/models/tokenizer.json", command.valueAfter("--tokenizer"))
        assertEquals("cpu", command.valueAfter("--backend"))
        assertEquals(1, command.count { it == "--mmap" })
        assertEquals("20", command.valueAfter("--steps"))
        assertEquals("4294967296", command.valueAfter("--seed"))
        assertEquals("3", command.valueAfter("--cfg-scale"))
        assertEquals("5", command.valueAfter("--flow-shift"))
        assertEquals("euler", command.valueAfter("--sampling-method"))
        assertEquals("discrete", command.valueAfter("--scheduler"))
        assertEquals("6", command.valueAfter("-t"))
        assertFalse(command.contains("--llm_vision"))
    }

    @Test
    fun preservesUnicodeQuotesAndLayoutJsonWithoutShellEscaping() {
        val layout = "{\"summary\":{\"caption\":\"你好\"},\"items\":[{\"text\":\"MOSS\\n手机\"}]}"
        val command = buildSdCliMossCommand(options().copy(prompt = layout))

        assertEquals(layout, command.valueAfter("-p"))
        assertEquals("watermark", command.valueAfter("-n"))
        assertFalse(command.any { it.startsWith("http://") || it.startsWith("https://") })
    }

    @Test
    fun rejectsInvalidLocalParametersInsteadOfChangingThem() {
        assertThrows(IllegalArgumentException::class.java) {
            buildSdCliMossCommand(options().copy(width = 500))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSdCliMossCommand(options().copy(steps = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildSdCliMossCommand(options().copy(seed = -1))
        }
    }

    private fun List<String>.valueAfter(flag: String): String {
        val index = lastIndexOf(flag)
        check(index >= 0 && index + 1 < size) { "missing $flag" }
        return this[index + 1]
    }
}
