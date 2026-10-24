package com.tiggy.qwenimage.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageJobBackendTest {
    @Test
    fun mossJobRoundTripPreservesBackendAndLayoutSettings() {
        val moss = MossSettings(
            prompt = "A poster with a red apple",
            checkpoint = MossCheckpoint.Base,
            inputMode = MossInputMode.Layout,
            promptUpsampler = true,
            layout = MossLayout(
                items = listOf(
                    MossLayoutItem(MossLayoutLabel.Text, "Large title", "FRESH", 0.1, 0.2, 0.9, 0.4)
                )
            )
        )
        val original = GenerationJob(
            id = "moss-1",
            settings = GenerationSettings(prompt = moss.prompt),
            backend = ImageGenerationBackend.MossWorkstation,
            mossSettings = moss,
            outputWidth = 1024,
            outputHeight = 768
        )

        val restored = GenerationJob.fromJson(original.toJson())

        assertEquals(ImageGenerationBackend.MossWorkstation, restored.backend)
        assertEquals(MossCheckpoint.Base, restored.mossSettings?.checkpoint)
        assertEquals(MossInputMode.Layout, restored.mossSettings?.inputMode)
        assertEquals("FRESH", restored.mossSettings?.layout?.items?.single()?.text)
        assertEquals(1024, restored.outputWidth)
        assertEquals(768, restored.outputHeight)
    }

    @Test
    fun legacyJobWithoutBackendDefaultsToQwen() {
        val json = GenerationJob("legacy", GenerationSettings(prompt = "apple")).toJson().apply {
            remove("backend")
            remove("mossSettings")
        }

        val restored = GenerationJob.fromJson(json)

        assertEquals(ImageGenerationBackend.QwenLocal, restored.backend)
        assertNull(restored.mossSettings)
    }

    @Test
    fun layoutPromptRemainsExactJsonWhenUpsamplerWasEnabled() {
        val settings = MossSettings(
            prompt = "A clean poster",
            inputMode = MossInputMode.Layout,
            promptUpsampler = true,
            layout = MossLayout(items = listOf(MossLayoutItem(MossLayoutLabel.Object, "A red apple", left = 0.2, top = 0.2, right = 0.8, bottom = 0.8)))
        )
        val workflow = com.tiggy.qwenimage.engine.MossWorkflow.build(settings)
        val positive = workflow.getJSONObject("7").getJSONObject("inputs").getString("text")

        assertEquals("abs_centralized", JSONObject(positive).getString("bbox_coord_space"))
        assertEquals(-307, JSONObject(positive).getJSONArray("items").getJSONObject(0).getJSONArray("bbox").getInt(1))
    }
}
