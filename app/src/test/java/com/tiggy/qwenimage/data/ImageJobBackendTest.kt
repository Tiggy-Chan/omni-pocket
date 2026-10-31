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
            inputMode = MossInputMode.Layout,
            layout = MossLayout(
                items = listOf(
                    MossLayoutItem(MossLayoutLabel.Text, "Large title", "FRESH", 0.1, 0.2, 0.9, 0.4)
                )
            ),
            local = MossLocalSettings(width = 768, height = 512)
        )
        val original = GenerationJob(
            id = "moss-1",
            settings = GenerationSettings(prompt = moss.prompt),
            backend = ImageGenerationBackend.MossLocal,
            mossSettings = moss,
            outputWidth = 768,
            outputHeight = 512
        )

        val restored = GenerationJob.fromJson(original.toJson())

        assertEquals(ImageGenerationBackend.MossLocal, restored.backend)
        assertEquals(MossInputMode.Layout, restored.mossSettings?.inputMode)
        assertEquals("FRESH", restored.mossSettings?.layout?.items?.single()?.text)
        assertEquals(768, restored.mossSettings?.local?.width)
        assertEquals(768, restored.outputWidth)
        assertEquals(512, restored.outputHeight)
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
    fun jobsFromTheRemovedWorkstationModeStayMoss() {
        val json = GenerationJob("old", GenerationSettings(prompt = "apple"), backend = ImageGenerationBackend.MossLocal)
            .toJson()
            .put("backend", "MossWorkstation")

        assertEquals(ImageGenerationBackend.MossLocal, GenerationJob.fromJson(json).backend)
    }

    @Test
    fun legacySettingsWithWorkstationFieldsStillParse() {
        val legacy = JSONObject()
            .put("serverUrl", "http://127.0.0.1:8188")
            .put("checkpoint", "Base")
            .put("prompt", "poster")
            .put("width", 1024)
            .put("local", JSONObject().put("width", 768).put("height", 768))

        val restored = MossSettings.fromJson(legacy)

        assertEquals("poster", restored.prompt)
        assertEquals(768, restored.local.width)
        restored.validateLocal()
    }
}
