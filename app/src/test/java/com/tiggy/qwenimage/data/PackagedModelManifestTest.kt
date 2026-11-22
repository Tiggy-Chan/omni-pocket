package com.tiggy.qwenimage.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackagedModelManifestTest {
    @Test
    fun bundledWanManifestMatchesPinnedFilesAndHashes() {
        val manifest = PackagedModelManifest.fromJson(JSONObject(File("src/main/assets/wan_video.json").readText()))
        assertEquals("wan22-ti2v-5b-turbo-v1", manifest.modelId)
        assertEquals(3, manifest.artifacts.size)
        assertEquals(
            setOf("Wan2_2-TI2V-5B-Turbo-Q4_0.gguf", "umt5-xxl-encoder-Q3_K_S.gguf", "taew2_2.safetensors"),
            manifest.artifacts.map { it.path }.toSet()
        )
        assertEquals(
            3_033_897_696L + 2_858_489_696L + 22_848_048L,
            manifest.totalBytes
        )
    }

    @Test
    fun bundledYue2ManifestMatchesPinnedFilesAndHashes() {
        val manifest = PackagedModelManifest.fromJson(JSONObject(File("src/main/assets/yue2_music.json").readText()))
        assertEquals("yue2-3b-q4_0-v1", manifest.modelId)
        assertEquals(6, manifest.artifacts.size)
        assertTrue(manifest.artifacts.any { it.path == "yue2-3b-q4_0.gguf" && it.size == 2_665_641_504L })
        assertTrue(manifest.artifacts.any { it.path == "sidecars/yue2-qwen.tiktoken" && it.size == 2_561_218L })
    }

    @Test
    fun bundledElephantMetadataMatchesLlmCatalogHashes() {
        val catalog = LlmCatalog.parse(File("src/main/assets/llm_models.json").readText())
        val elephant = catalog.single { it.kind == LlmModelKind.Decision }
        val metadataFiles = elephant.artifacts.filterNot { it.path == LlmModelSpec.DECISION_WEIGHTS }
        assertEquals(4, metadataFiles.size)
        metadataFiles.forEach { artifact ->
            val assetFile = File("src/main/assets/elephant-1/${artifact.path}")
            assertTrue("missing asset ${artifact.path}", assetFile.isFile)
            assertEquals("size mismatch for ${artifact.path}", artifact.size, assetFile.length())
            assertEquals("sha256 mismatch for ${artifact.path}", artifact.sha256, ResumableHttpDownloader.sha256(assetFile))
        }
    }
}
