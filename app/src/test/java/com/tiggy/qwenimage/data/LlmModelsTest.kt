package com.tiggy.qwenimage.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LlmModelsTest {
    @Test
    fun bundledCatalogHasThreeChatModelsAndOneLocalDecisionModel() {
        val models = LlmCatalog.parse(File("src/main/assets/llm_models.json").readText())
        val chat = models.filter { it.kind == LlmModelKind.Chat }
        val decision = models.single { it.kind == LlmModelKind.Decision }

        assertEquals(listOf("qwen35-0.8b", "qwen35-2b", "qwen35-4b"), chat.map { it.id })
        assertTrue(models.all { it.revision.matches(Regex("[0-9a-f]{40}")) })
        assertTrue(chat.all { it.artifacts.map(LlmModelArtifact::path).toSet() == LlmModelSpec.REQUIRED_FILES.toSet() })
        assertEquals("elephant-1", decision.id)
        assertEquals("baselabs/elephant-1", decision.repo)
        assertTrue(decision.localOnly)
        assertTrue(decision.artifacts.all { it.url.isEmpty() })
        assertEquals(LlmModelSpec.DECISION_REQUIRED_FILES.toSet(),
            decision.artifacts.map(LlmModelArtifact::path).toSet())
        assertEquals("764ef1834ffe7721f11ff7ed2ba42c3616140493b7505e2928eee057496c95a4",
            decision.artifacts.single { it.path == LlmModelSpec.DECISION_WEIGHTS }.sha256)
        assertEquals(542_300_560L, models.first().totalBytes)
        assertEquals("4e25ef4cbfb49a33013d17300c2f68cd9c9ce8f2e3f7b947fd2c574ec297ca5e",
            models.first().artifacts.first { it.path == "llm.mnn.weight" }.sha256)
    }

    @Test
    fun rejectsUnpinnedRevisionAndUnexpectedFiles() {
        val model = LlmCatalog.parse(File("src/main/assets/llm_models.json").readText()).first().toJson()
        model.put("revision", "main")
        assertThrows(IllegalArgumentException::class.java) { LlmModelSpec.fromJson(model) }
        model.put("revision", "3208b55b2e3c948016c9d380a697be881a1f12f8")
        model.getJSONArray("files").getJSONObject(0).put("path", "../../config.json")
        assertThrows(IllegalArgumentException::class.java) { LlmModelSpec.fromJson(model) }
    }

    @Test
    fun manifestRoundTripRetainsIntegrityMetadata() {
        val model = LlmCatalog.parse(File("src/main/assets/llm_models.json").readText()).first()
        val restored = LlmCatalog.parse(LlmCatalog.manifest(model)).single()
        assertEquals(model, restored)
    }
}
