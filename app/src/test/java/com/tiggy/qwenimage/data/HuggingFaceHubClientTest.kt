package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceHubClientTest {
    @Test
    fun parsesGgufTreeAndOrdersQuantizationsWithLfsSha256() {
        val hashA = "a".repeat(64)
        val hashB = "b".repeat(64)
        val json = """
            [
              {"type":"file","path":"README.md","size":1200},
              {"type":"file","path":"mmproj-model-f16.gguf","size":500000000},
              {"type":"file","path":"model-Q8_0.gguf","size":2200000000,"lfs":{"oid":"$hashA","size":2200000000}},
              {"type":"file","path":"model-Q4_K_M.gguf","size":1200000000,"lfs":{"oid":"$hashB","size":1200000000}},
              {"type":"file","path":"model-Q4_K_M-00001-of-00002.gguf","size":600000000}
            ]
        """.trimIndent()

        val options = HuggingFaceHubClient.parseRepoTree("Qwen/Qwen3-1.7B-GGUF", "main", json, HubSearchCategory.GgufChat)
        assertEquals(3, options.size)
        // Q4_K_M single file should come first
        assertEquals("model-Q4_K_M.gguf", options[0].entryFile)
        assertEquals("Q4_K_M", options[0].quantTag)
        assertEquals(hashB, options[0].files.single().sha256)
        assertNull(options[0].splitWarning)
        // Q8_0 second
        assertEquals("model-Q8_0.gguf", options[1].entryFile)
        assertEquals("Q8_0", options[1].quantTag)
        // Split file last with warning
        assertNotNull(options[2].splitWarning)
    }

    @Test
    fun detectsCompleteMnnPackageBundle() {
        val json = """
            [
              {"type":"file","path":"config.json","size":652},
              {"type":"file","path":"llm_config.json","size":8000},
              {"type":"file","path":"llm.mnn","size":2000000},
              {"type":"file","path":"llm.mnn.weight","size":900000000},
              {"type":"file","path":"tokenizer.txt","size":6000000},
              {"type":"file","path":"README.md","size":1000}
            ]
        """.trimIndent()
        val options = HuggingFaceHubClient.parseRepoTree("taobao-mnn/Qwen3-1.7B-MNN", "main", json, HubSearchCategory.MnnChat)
        assertEquals(1, options.size)
        assertEquals(HubModelType.MnnChat, options.single().type)
        assertEquals(5, options.single().files.size)
        assertEquals("config.json", options.single().entryFile)
    }

    @Test
    fun filtersSingleFileSdImageCheckpointsAndExcludesSubcomponents() {
        val json = """
            [
              {"type":"file","path":"unet/diffusion_pytorch_model.safetensors","size":3000000000},
              {"type":"file","path":"vae/diffusion_pytorch_model.safetensors","size":300000000},
              {"type":"file","path":"sd_xl_turbo_1.0_fp16.safetensors","size":6938000000},
              {"type":"file","path":"tiny_lora.safetensors","size":50000000}
            ]
        """.trimIndent()
        val options = HuggingFaceHubClient.parseRepoTree("stabilityai/sdxl-turbo", "main", json, HubSearchCategory.SdImage)
        assertEquals(1, options.size)
        assertEquals("sd_xl_turbo_1.0_fp16.safetensors", options.single().entryFile)
        assertEquals(HubModelType.SdImage, options.single().type)
    }

    @Test
    fun parsesSearchApiResultsAndGatedFlag() {
        val json = """
            [
              {"id":"Qwen/Qwen3-1.7B-GGUF","downloads":50000,"likes":120,"pipeline_tag":"text-generation","tags":["gguf"],"gated":false},
              {"id":"meta-llama/Llama-3.2-3B","downloads":90000,"likes":500,"pipeline_tag":"text-generation","tags":["gguf"],"gated":"manual"}
            ]
        """.trimIndent()
        val list = HuggingFaceHubClient.parseSearchResults(json, HubSearchCategory.GgufChat)
        assertEquals(2, list.size)
        assertEquals("Qwen", list[0].author)
        assertEquals(false, list[0].gated)
        assertTrue(list[1].gated)
    }
}
