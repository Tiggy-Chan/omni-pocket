package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.GgufHeaderInfo
import com.tiggy.qwenimage.data.GgufHeaderParser
import com.tiggy.qwenimage.data.HubSearchCategory
import com.tiggy.qwenimage.data.HuggingFaceHubClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VlmAndImg2ImgTest {

    @Test
    fun `gguf parser classifies mmproj projector files`() {
        val header = GgufHeaderInfo(version = 3, tensorCount = 120, kvCount = 18, architecture = "clip", modelName = "Qwen3-VL-4B-mmproj")
        val byHeader = GgufHeaderParser.classifyFile("mmproj-F16.gguf", header)
        assertTrue(byHeader.isMmproj)
        assertEquals("mmproj", byHeader.archHint)

        val chatHeader = GgufHeaderInfo(version = 3, tensorCount = 300, kvCount = 30, architecture = "qwen3", modelName = "Qwen3-4B")
        val normalChat = GgufHeaderParser.classifyFile("Qwen3-4B-Q4_K_M.gguf", chatHeader)
        assertFalse(normalChat.isMmproj)
    }

    @Test
    fun `hf repo tree parser bundles preferred mmproj companion with vlm gguf options`() {
        val treeJson = """
            [
              {"type":"file","path":"Qwen3-VL-2B-Q4_K_M.gguf","size":1500000000},
              {"type":"file","path":"mmproj-F16.gguf","size":800000000},
              {"type":"file","path":"mmproj-Q8_0.gguf","size":450000000}
            ]
        """.trimIndent()
        val options = HuggingFaceHubClient.parseRepoTree("unsloth/Qwen3-VL-2B-GGUF", "main", treeJson, HubSearchCategory.GgufChat)
        assertEquals(1, options.size)
        val opt = options.first()
        assertEquals(2, opt.files.size)
        assertEquals("mmproj-Q8_0.gguf", opt.files[1].path)
        assertTrue(opt.metadata["mmprojFile"]?.contains("mmproj-Q8_0.gguf") == true)
        assertEquals(1_950_000_000L, opt.totalBytes)
    }

    @Test
    fun `generic sd image command emits init-img and strength for img2img`() {
        val args = buildSdCliGenericImageCommand(
            SdCliGenericImageOptions(
                modelFile = File("/tmp/sdxl.safetensors"),
                output = File("/tmp/out.png"),
                prompt = "watercolor landscape",
                negativePrompt = "blurry",
                width = 512,
                height = 512,
                steps = 12,
                seed = 7,
                cfg = 6.5,
                initImage = File("/tmp/source.png"),
                strength = 0.65
            )
        )
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("/tmp/source.png"))
        assertTrue(args.contains("--strength"))
        assertTrue(args.contains("0.65"))
        assertFalse(args.contains("-r"))
    }
}
