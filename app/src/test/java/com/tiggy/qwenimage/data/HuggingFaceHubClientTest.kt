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
        val options = HuggingFaceHubClient.parseRepoTree("taobao-mnn/Qwen2.5-3B-Instruct-MNN", "main", json, HubSearchCategory.MnnChat)
        assertEquals(1, options.size)
        assertEquals(HubModelType.MnnChat, options.single().type)
        assertEquals(5, options.single().files.size)
        assertEquals("config.json", options.single().entryFile)
    }

    @Test
    fun detectsModernMnnPackageBundleWithBinaryMtokTokenizer() {
        val json = """
            [
              {"type":"file","path":"config.json","size":457},
              {"type":"file","path":"llm_config.json","size":4867},
              {"type":"file","path":"llm.mnn","size":1285272},
              {"type":"file","path":"llm.mnn.weight","size":1124274658},
              {"type":"file","path":"embeddings_int4.bin","size":145858560},
              {"type":"file","path":"tokenizer.mtok","size":4111426},
              {"type":"file","path":"llm.mnn.json","size":3083331}
            ]
        """.trimIndent()
        val options = HuggingFaceHubClient.parseRepoTree("taobao-mnn/Qwen3-1.7B-MNN", "main", json, HubSearchCategory.MnnChat)
        assertEquals(1, options.size)
        val bundle = options.single()
        assertEquals(HubModelType.MnnChat, bundle.type)
        assertEquals(6, bundle.files.size)
        assertTrue(bundle.files.any { it.path == "tokenizer.mtok" })
        assertTrue(bundle.files.any { it.path == "embeddings_int4.bin" })
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

    @Test
    fun attachesVerifiedCompanionsForModularZImageAndFlux2Klein() {
        val zImageJson = """
            [
              {"type":"file","path":"z-image-turbo-Q4_0.gguf","size":3700000000}
            ]
        """.trimIndent()
        val zOpts = HuggingFaceHubClient.parseRepoTree("unsloth/Z-Image-Turbo-GGUF", "main", zImageJson, HubSearchCategory.SdImage)
        assertEquals(1, zOpts.size)
        val zOpt = zOpts.single()
        assertEquals("z_image", zOpt.metadata["arch"])
        assertEquals("8", zOpt.metadata["defaultSteps"])
        assertEquals("1.0", zOpt.metadata["defaultCfg"])
        assertEquals(3, zOpt.files.size)
        assertTrue(zOpt.files.any { it.sharedCompanionKey == "Qwen3-4B-Instruct-2507-Q4_K_M.gguf" })
        assertTrue(zOpt.files.any { it.sharedCompanionKey == "flux1_ae.safetensors" })

        val fluxJson = """
            [
              {"type":"file","path":"flux-2-klein-4b-Q4_K_M.gguf","size":2500000000}
            ]
        """.trimIndent()
        val fOpts = HuggingFaceHubClient.parseRepoTree("unsloth/FLUX.2-klein-4B-GGUF", "main", fluxJson, HubSearchCategory.SdImage)
        assertEquals(1, fOpts.size)
        assertEquals("flux2_klein", fOpts.single().metadata["arch"])
        assertEquals("4", fOpts.single().metadata["defaultSteps"])
        assertTrue(fOpts.single().files.any { it.sharedCompanionKey == "flux2_ae.safetensors" })
    }

    @Test
    fun extractsWanVideoOptionsWithCompanionsAndFlagsMoE14B() {
        val json = """
            [
              {"type":"file","path":"Wan2.1-T2V-1.3B-Q4_K_M.gguf","size":950000000},
              {"type":"file","path":"Wan2.2-T2V-A14B-LowNoise-Q4_K_M.gguf","size":8000000000},
              {"type":"file","path":"umt5-xxl-encoder-Q3_K_S.gguf","size":2858489696}
            ]
        """.trimIndent()
        val opts = HuggingFaceHubClient.parseRepoTree("samuelchristlie/Wan2.1-T2V-1.3B-GGUF", "main", json, HubSearchCategory.VideoWan)
        assertEquals(2, opts.size)
        assertNull(opts[0].splitWarning)
        assertEquals("wan21_t2v", opts[0].metadata["arch"])
        assertEquals("taew2_1.safetensors", opts[0].metadata["taeCompanion"])
        assertEquals(3, opts[0].files.size)
        assertNotNull(opts[1].splitWarning)
    }

    @Test
    fun extractsMultiFamilyMusicAudioOptionsFromAudioCppRepo() {
        val json = """
            [
              {"type":"file","path":"ACE-Step-1.5-Turbo-Q8_0.gguf","size":6190000000},
              {"type":"file","path":"HeartMuLa-Q8_0.gguf","size":7660000000},
              {"type":"file","path":"Stable-Audio-3-Small-Music-Q8_0.gguf","size":1680000000}
            ]
        """.trimIndent()
        val opts = HuggingFaceHubClient.parseRepoTree("audio-cpp/audio.cpp-gguf", "main", json, HubSearchCategory.MusicAudio)
        assertEquals(3, opts.size)
        val families = opts.map { it.metadata["family"] }.toSet()
        assertEquals(setOf("ace_step", "heartmula", "stable_audio"), families)
        val ace = opts.first { it.metadata["family"] == "ace_step" }
        assertEquals("acestep-v15-turbo", ace.metadata["ditVariant"])
    }

    @Test
    fun extractsDecisionGgufOptionsWithAttentionModeMetadata() {
        val onejevJson = """
            [
              {"type":"file","path":"OneJev1.6-4B.Q4_K_M.gguf","size":2520000000},
              {"type":"file","path":"mmproj-model-f16.gguf","size":500000000}
            ]
        """.trimIndent()
        val onejevOpts = HuggingFaceHubClient.parseRepoTree("mradermacher/OneJev1.6-4B-GGUF", "main", onejevJson, HubSearchCategory.DecisionGguf)
        assertEquals(1, onejevOpts.size)
        assertEquals(HubModelType.DecisionGguf, onejevOpts.single().type)
        assertEquals("causal", onejevOpts.single().metadata["attentionMode"])
        assertEquals(HubCompanionNote.DecisionCausal, onejevOpts.single().companionNoteKey)

        val elephantJson = """
            [
              {"type":"file","path":"elephant-1.Q4_K_M.gguf","size":5629109376}
            ]
        """.trimIndent()
        val elephantOpts = HuggingFaceHubClient.parseRepoTree("mradermacher/elephant-1-GGUF", "main", elephantJson, HubSearchCategory.DecisionGguf)
        assertEquals(1, elephantOpts.size)
        assertEquals("block_bidirectional", elephantOpts.single().metadata["attentionMode"])
        assertEquals(HubCompanionNote.DecisionBlockBidir, elephantOpts.single().companionNoteKey)
    }

    @Test
    fun extractsMiniMaxH3PrunedWithPiH3CompanionsAndFlagsUnpruned() {
        val json = """
            [
              {"type":"file","path":"minimax_h3_fl2va_pruned-Q2_K.gguf","size":6720000000},
              {"type":"file","path":"minimax_h3_fl2va-Q4_K_M.gguf","size":19600000000},
              {"type":"file","path":"minimax_h3_image-Q4_K_M.gguf","size":6000000000},
              {"type":"file","path":"qwen3vl_32b_minimax_h3-Q2_K_M.gguf","size":13000000000}
            ]
        """.trimIndent()
        val opts = HuggingFaceHubClient.parseRepoTree("unsloth/MiniMax-H3-GGUF", "main", json, HubSearchCategory.VideoWan)
        assertEquals(2, opts.size)
        val pruned = opts[0]
        assertNull(pruned.splitWarning)
        assertEquals("minimax_h3", pruned.metadata["arch"])
        assertEquals("2", pruned.metadata["defaultSteps"])
        assertEquals("12.0", pruned.metadata["defaultShift"])
        assertEquals("22", pruned.metadata["defaultFrames"])
        assertEquals(6, pruned.files.size)
        assertEquals(12, pruned.recommendedRamGb)
        assertEquals(HubCompanionNote.MiniMaxH3PiShared, pruned.companionNoteKey)
        assertTrue(pruned.files.any { it.sharedCompanionKey == "qwen3vl-32B-MiniMax-H3-Q2_K.gguf" })
        assertTrue(pruned.files.any { it.sharedCompanionKey == "minimax_h3_video_vae_int8_convrot.safetensors" })
        assertTrue(pruned.files.any { it.sharedCompanionKey == "minimax_h3_audio_vae_fp32.safetensors" })
        assertTrue(pruned.files.any { it.sharedCompanionKey == "pi_h3_2step_adapter.safetensors" })
        assertTrue(pruned.files.any { it.sharedCompanionKey == "pi_h3_4step_adapter.safetensors" })

        val unpruned = opts[1]
        assertNotNull(unpruned.splitWarning)
        assertEquals(HubSplitWarning.MiniMaxH3Unpruned, unpruned.splitWarningKey)
    }

    @Test
    fun extractsFlux3KleinErnieImageAndFlux2Klein9BWithVerifiedCompanions() {
        val flux3Json = """[{"type":"file","path":"flux-3-image-klein-UD-Q4_K_XL.gguf","size":4000000000}]"""
        val flux3Opts = HuggingFaceHubClient.parseRepoTree("unsloth/flux-3-image-klein-GGUF", "main", flux3Json, HubSearchCategory.SdImage)
        assertEquals(1, flux3Opts.size)
        val f3 = flux3Opts.single()
        assertEquals("flux3_klein", f3.metadata["arch"])
        assertEquals("true", f3.metadata["supportsEdit"])
        assertEquals("UD-Q4_K_XL", f3.quantTag)
        assertEquals(HubCompanionNote.Flux3KleinShared, f3.companionNoteKey)
        assertTrue(f3.files.any { it.sharedCompanionKey == "Qwen3VL-4B-Instruct-Q4_K_M.gguf" })
        assertTrue(f3.files.any { it.sharedCompanionKey == "flux2_ae.safetensors" })

        val ernieJson = """[{"type":"file","path":"ERNIE-Image-Turbo-Q4_K_M.gguf","size":3800000000}]"""
        val ernieOpts = HuggingFaceHubClient.parseRepoTree("unsloth/ERNIE-Image-Turbo-GGUF", "main", ernieJson, HubSearchCategory.SdImage)
        assertEquals(1, ernieOpts.size)
        val ernie = ernieOpts.single()
        assertEquals("ernie_image", ernie.metadata["arch"])
        assertEquals("8", ernie.metadata["defaultSteps"])
        assertEquals(HubCompanionNote.ErnieImageShared, ernie.companionNoteKey)
        assertTrue(ernie.files.any { it.sharedCompanionKey == "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf" })
        assertTrue(ernie.files.any { it.sharedCompanionKey == "flux2_ae.safetensors" })

        val flux29bJson = """[{"type":"file","path":"flux-2-klein-9b-Q4_K_M.gguf","size":5600000000}]"""
        val f9Opts = HuggingFaceHubClient.parseRepoTree("unsloth/FLUX.2-klein-9B-GGUF", "main", flux29bJson, HubSearchCategory.SdImage)
        assertEquals(1, f9Opts.size)
        val f9 = f9Opts.single()
        assertEquals("flux2_klein_9b", f9.metadata["arch"])
        assertEquals("true", f9.metadata["supportsEdit"])
        assertEquals(HubCompanionNote.Flux2Klein9BShared, f9.companionNoteKey)
        assertTrue(f9.files.any { it.sharedCompanionKey == "Qwen3-8B-Q4_K_M.gguf" })
    }

    @Test
    fun parsesUnslothDynamicXlQuantTagsAndMiniMaxH3Ref2VaMode() {
        assertEquals("UD-Q2_K_XL", HuggingFaceHubClient.extractQuantTag("minimax_h3_ref2va_pruned-UD-Q2_K_XL.gguf"))
        assertEquals("Q4_K_XL", HuggingFaceHubClient.extractQuantTag("gemma-4-E4B-it-Q4_K_XL.gguf"))

        val json = """[
            {"type":"file","path":"minimax_h3_ref2va_pruned-UD-Q2_K_XL.gguf","size":7200000000},
            {"type":"file","path":"minimax_h3_fl2va_pruned-Q2_K.gguf","size":6720000000}
        ]"""
        val opts = HuggingFaceHubClient.parseRepoTree("unsloth/MiniMax-H3-GGUF", "main", json, HubSearchCategory.VideoWan)
        assertEquals(2, opts.size)
        val refOpt = opts.first { it.entryFile.contains("ref2va") }
        val flOpt = opts.first { it.entryFile.contains("fl2va") }
        assertEquals("ref2va", refOpt.metadata["h3Mode"])
        assertEquals("UD-Q2_K_XL", refOpt.quantTag)
        assertEquals("fl2va", flOpt.metadata["h3Mode"])
    }
}
