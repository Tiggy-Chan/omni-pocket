package com.tiggy.qwenimage.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MossModelManifestTest {
    @Test
    fun parsesBundledManifestWithAllFourPinnedFiles() {
        val manifest = MossModelManifest.fromJson(bundledJson())

        assertEquals("moss-image-local-v1", manifest.modelId)
        assertEquals(listOf(
            MossModelArtifact(
                "MOSS-Image-SFT-Q4_0.gguf",
                3_327_400_800L,
                "b59d2461fa431d670471f74ab54fd90a0bfb36799fd3ffbec1cf103568a883b8",
                "https://huggingface.co/leejet/MOSS-Image-SFT-GGUF/resolve/main/MOSS-Image-SFT-Q4_0.gguf"
            ),
            MossModelArtifact(
                "Qwen3.5-9B-Q4_K_M.gguf",
                5_680_522_464L,
                "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",
                "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf"
            ),
            MossModelArtifact(
                "flux2-vae_small.safetensors",
                249_521_340L,
                "d8d52ba036475f5fb07c8b435e176d3d97ebfa82f0d1a1c317f9cc1e25bd013b",
                "https://huggingface.co/Comfy-Org/MOSS-Image/resolve/main/vae/flux2-vae_small.safetensors"
            ),
            MossModelArtifact(
                "tokenizer.json",
                19_989_325L,
                "06b9509352d2af50381ab2247e083b80d32d5c0aba91c272ca9ff729b6a0e523",
                "https://huggingface.co/OpenMOSS-Team/MOSS-Image-Base/resolve/main/tokenizer/tokenizer.json"
            )
        ), manifest.artifacts)
        assertTrue(MossModelManifest.fromJson(manifest.toJson()).artifacts.containsAll(manifest.artifacts))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDirectoryTraversal() {
        val json = bundledJson()
        file(json, 0).put("path", "../MOSS-Image-SFT-Q4_0.gguf")
        MossModelManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncorrectButWellFormedHash() {
        val json = bundledJson()
        file(json, 0).put("sha256", "0".repeat(64))
        MossModelManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedHash() {
        val json = bundledJson()
        file(json, 0).put("sha256", "not-a-sha256")
        MossModelManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateFiles() {
        val json = bundledJson()
        file(json, 1).put("path", file(json, 0).getString("path"))
        MossModelManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingFiles() {
        val json = bundledJson()
        json.getJSONArray("files").remove(3)
        MossModelManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncorrectSize() {
        val json = bundledJson()
        file(json, 2).put("size", file(json, 2).getLong("size") - 1)
        MossModelManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncorrectSourceUrl() {
        val json = bundledJson()
        file(json, 3).put("url", "https://example.com/tokenizer.json")
        MossModelManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnotherModelId() {
        val json = bundledJson()
        json.put("modelId", "another-model")
        MossModelManifest.fromJson(json)
    }

    private fun bundledJson() = JSONObject(File("src/main/assets/moss_image.json").readText())

    private fun file(json: JSONObject, index: Int) = json.getJSONArray("files").getJSONObject(index)
}
