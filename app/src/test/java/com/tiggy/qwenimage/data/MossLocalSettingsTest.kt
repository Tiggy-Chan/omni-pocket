package com.tiggy.qwenimage.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MossLocalSettingsTest {
    @Test
    fun localDefaultsAreValid() {
        val settings = MossSettings(prompt = "a red apple")

        assertEquals(512, settings.local.width)
        assertEquals(512, settings.local.height)
        assertEquals(3.0, settings.local.cfg, 0.0)
        settings.validateLocal()
    }

    @Test
    fun layoutUsesTheLocalCanvas() {
        val item = MossLayoutItem(
            label = MossLayoutLabel.Text,
            caption = "title",
            text = "MOSS",
            left = 0.25,
            top = 0.25,
            right = 0.75,
            bottom = 0.75
        )
        val settings = MossSettings(
            prompt = "poster",
            inputMode = MossInputMode.Layout,
            layout = MossLayout(items = listOf(item))
        )

        val bbox = JSONObject(settings.localModelPrompt()).getJSONArray("items").getJSONObject(0).getJSONArray("bbox")

        assertEquals(listOf(-128, -128, 128, 128), (0 until bbox.length()).map { bbox.getInt(it) })
    }

    @Test
    fun rejectsInvalidLocalResources() {
        assertThrows(IllegalArgumentException::class.java) {
            MossSettings(prompt = "poster", local = MossLocalSettings(width = 2048, height = 2048)).validateLocal()
        }
        assertThrows(IllegalArgumentException::class.java) {
            MossSettings(prompt = "poster", local = MossLocalSettings(cfg = Double.NaN)).validateLocal()
        }
        assertThrows(IllegalArgumentException::class.java) {
            MossSettings(prompt = "", local = MossLocalSettings()).validateLocal()
        }
    }
}
