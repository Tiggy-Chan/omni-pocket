package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.MOSS_TEXT_ENCODER
import com.tiggy.qwenimage.data.MOSS_VAE
import com.tiggy.qwenimage.data.MossCheckpoint
import com.tiggy.qwenimage.data.MossInputMode
import com.tiggy.qwenimage.data.MossLayout
import com.tiggy.qwenimage.data.MossLayoutItem
import com.tiggy.qwenimage.data.MossLayoutLabel
import com.tiggy.qwenimage.data.MossSettings
import com.tiggy.qwenimage.data.MossSlgSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MossWorkflowTest {
    private fun settings() = MossSettings(prompt = "a red apple", promptUpsampler = false)

    private fun input(workflow: JSONObject, nodeId: String, name: String): Any =
        workflow.getJSONObject(nodeId).getJSONObject("inputs").get(name)

    private fun assertLink(workflow: JSONObject, nodeId: String, inputName: String, sourceId: String) {
        val link = input(workflow, nodeId, inputName) as JSONArray
        assertEquals(sourceId, link.getString(0))
        assertEquals(0, link.getInt(1))
    }

    private fun positiveText(workflow: JSONObject): Any = input(workflow, "7", "text")

    private fun layoutItem() = MossLayoutItem(
        label = MossLayoutLabel.Text,
        caption = "A centered title",
        text = "MOSS",
        left = 0.25,
        top = 0.25,
        right = 0.75,
        bottom = 0.75
    )

    private fun layoutSettings(width: Int, height: Int) = settings().copy(
        width = width,
        height = height,
        inputMode = MossInputMode.Layout,
        promptUpsampler = true,
        layout = MossLayout(items = listOf(layoutItem()))
    )

    private fun modelLayout(workflow: JSONObject): JSONObject = JSONObject(positiveText(workflow) as String)

    @Test
    fun nativeGraphUsesOfficialFastDefaultsAndConnections() {
        val workflow = MossWorkflow.build(settings())
        val types = workflow.keys().asSequence().map { workflow.getJSONObject(it).getString("class_type") }.toSet()

        assertEquals(11, workflow.length())
        assertEquals(
            setOf(
                "UNETLoader", "CLIPLoader", "VAELoader", "CLIPTextEncode", "EmptyFlux2LatentImage",
                "ModelSamplingAuraFlow", "SkipLayerGuidanceDiTSimple", "KSampler", "VAEDecode", "SaveImage"
            ),
            types
        )
        assertFalse(types.contains("ModelSamplingSD3"))
        assertEquals(MOSS_TEXT_ENCODER, input(workflow, "2", "clip_name"))
        assertEquals("moss_image", input(workflow, "2", "type"))
        assertEquals(MOSS_VAE, input(workflow, "3", "vae_name"))
        assertEquals(25, input(workflow, "10", "steps"))
        assertEquals(2.5, (input(workflow, "10", "cfg") as Number).toDouble(), 0.0)
        assertEquals("er_sde", input(workflow, "10", "sampler_name"))
        assertEquals("simple", input(workflow, "10", "scheduler"))
        assertEquals(5.0, (input(workflow, "4", "shift") as Number).toDouble(), 0.0)
        assertEquals("22", input(workflow, "5", "single_layers"))
        assertEquals(0.0, (input(workflow, "5", "start_percent") as Number).toDouble(), 0.0)
        assertEquals(0.5, (input(workflow, "5", "end_percent") as Number).toDouble(), 0.0)

        assertLink(workflow, "4", "model", "1")
        assertLink(workflow, "5", "model", "4")
        assertLink(workflow, "10", "model", "5")
        assertLink(workflow, "10", "positive", "7")
        assertLink(workflow, "10", "negative", "8")
        assertLink(workflow, "10", "latent_image", "9")
        assertLink(workflow, "11", "samples", "10")
        assertLink(workflow, "11", "vae", "3")
        assertLink(workflow, "12", "images", "11")
    }

    @Test
    fun baseAndSftShareTheGraphAndOnlySwapTheUnetFile() {
        val sft = MossWorkflow.build(settings().copy(checkpoint = MossCheckpoint.Sft))
        val base = MossWorkflow.build(settings().copy(checkpoint = MossCheckpoint.Base))

        assertEquals("moss_image_sft_int8_convrot.safetensors", input(sft, "1", "unet_name"))
        assertEquals("moss_image_base_int8_convrot.safetensors", input(base, "1", "unet_name"))
        base.getJSONObject("1").getJSONObject("inputs").put("unet_name", input(sft, "1", "unet_name"))
        assertEquals(sft.toString(), base.toString())
    }

    @Test
    fun upsamplerUsesTheSameClipAndEveryOfficialInput() {
        val workflow = MossWorkflow.build(settings().copy(prompt = "保留 \"MOSS\"", promptUpsampler = true, seed = 123))
        val inputs = workflow.getJSONObject("6").getJSONObject("inputs")

        assertEquals("TextGenerate", workflow.getJSONObject("6").getString("class_type"))
        assertEquals(
            setOf(
                "clip", "prompt", "system_prompt", "max_length", "sampling_mode", "temperature", "top_k",
                "top_p", "min_p", "repetition_penalty", "seed", "presence_penalty", "thinking",
                "use_default_template", "mtp"
            ),
            inputs.keySet()
        )
        assertLink(workflow, "6", "clip", "2")
        assertLink(workflow, "7", "text", "6")
        assertEquals("保留 \"MOSS\"", inputs.getString("prompt"))
        assertEquals(4096, inputs.getInt("max_length"))
        assertEquals("on", inputs.getString("sampling_mode"))
        assertEquals(0.7, inputs.getDouble("temperature"), 0.0)
        assertEquals(64, inputs.getInt("top_k"))
        assertEquals(0.95, inputs.getDouble("top_p"), 0.0)
        assertEquals(0.05, inputs.getDouble("min_p"), 0.0)
        assertEquals(1.0, inputs.getDouble("repetition_penalty"), 0.0)
        assertEquals(123L, inputs.getLong("seed"))
        assertEquals(0.0, inputs.getDouble("presence_penalty"), 0.0)
        assertFalse(inputs.getBoolean("thinking"))
        assertTrue(inputs.getBoolean("use_default_template"))
        assertEquals("auto", inputs.getString("mtp"))
    }

    @Test
    fun disabledUpsamplerPassesThePromptVerbatim() {
        val prompt = "  exact line 1\nexact line 2  "
        val workflow = MossWorkflow.build(settings().copy(prompt = prompt, promptUpsampler = false))

        assertFalse(workflow.has("6"))
        assertEquals(prompt, positiveText(workflow))
    }

    @Test
    fun layoutAlwaysBypassesUpsamplerAndEmitsOfficialJson() {
        val objectItem = MossLayoutItem(
            label = MossLayoutLabel.Object,
            caption = "A red apple on a table",
            left = 0.1,
            top = 0.5,
            right = 0.4,
            bottom = 0.9
        )
        val workflow = MossWorkflow.build(
            layoutSettings(1024, 1024).copy(
                prompt = "一张写着 MOSS 的海报",
                layout = MossLayout(meta = listOf("poster", "minimal"), items = listOf(layoutItem(), objectItem))
            )
        )

        assertFalse(workflow.has("6"))
        assertTrue(positiveText(workflow) is String)
        val layout = modelLayout(workflow)
        val summary = layout.getJSONObject("summary")
        assertEquals("一张写着 MOSS 的海报", summary.getString("caption"))
        assertEquals("single", summary.getString("canvas"))
        assertEquals("minimal", summary.getJSONArray("meta").getString(1))
        assertEquals("abs_centralized", layout.getString("bbox_coord_space"))
        assertEquals("yxyx", layout.getString("bbox_format"))
        assertEquals("text", layout.getJSONArray("items").getJSONObject(0).getString("label"))
        assertEquals("MOSS", layout.getJSONArray("items").getJSONObject(0).getString("text"))
        assertFalse(layout.getJSONArray("items").getJSONObject(1).has("text"))
        assertEquals("object", layout.getJSONArray("items").getJSONObject(1).getString("label"))
    }

    @Test
    fun layoutCoordinatesAreCenteredForSquareAndNonSquareCanvases() {
        val square = modelLayout(MossWorkflow.build(layoutSettings(1024, 1024)))
            .getJSONArray("items").getJSONObject(0).getJSONArray("bbox")
        val wide = modelLayout(MossWorkflow.build(layoutSettings(1216, 832)))
            .getJSONArray("items").getJSONObject(0).getJSONArray("bbox")

        assertEquals(listOf(-256, -256, 256, 256), (0 until square.length()).map { square.getInt(it) })
        assertEquals(listOf(-208, -304, 208, 304), (0 until wide.length()).map { wide.getInt(it) })
    }

    @Test
    fun settingsRoundTripRestoresEveryGenerationField() {
        val original = layoutSettings(1216, 832).copy(
            serverUrl = "http://127.0.0.1:18188",
            checkpoint = MossCheckpoint.Base,
            prompt = "完整画面",
            negativePrompt = "blur",
            seed = Long.MAX_VALUE - 1,
            steps = 50,
            cfg = 3.0,
            shift = 6.5,
            sampler = "euler",
            scheduler = "sgm_uniform",
            slg = MossSlgSettings(enabled = false, singleLayers = "22, 24", startPercent = 0.1, endPercent = 0.8),
            promptUpsampler = true,
            inputMode = MossInputMode.Layout
        )

        val restored = MossSettings.fromJson(JSONObject(original.toJson().toString()))

        assertEquals(original, restored)
        assertTrue(restored.promptUpsampler)
        assertFalse(restored.usesPromptUpsampler)
        assertEquals(
            setOf(
                "serverUrl", "checkpoint", "prompt", "negativePrompt", "layout", "width", "height", "seed",
                "steps", "cfg", "shift", "sampler", "scheduler", "slg", "promptUpsampler", "inputMode"
            ),
            original.toJson().keySet()
        )
    }

    @Test
    fun officialEulerQualitySettingsRemainSelectable() {
        val workflow = MossWorkflow.build(
            settings().copy(steps = 50, cfg = 3.0, shift = 7.0, sampler = "euler", scheduler = "normal")
        )

        assertEquals(50, input(workflow, "10", "steps"))
        assertEquals(3.0, (input(workflow, "10", "cfg") as Number).toDouble(), 0.0)
        assertEquals(7.0, (input(workflow, "4", "shift") as Number).toDouble(), 0.0)
        assertEquals("euler", input(workflow, "10", "sampler_name"))
        assertEquals("normal", input(workflow, "10", "scheduler"))
    }

    @Test
    fun slgCanBeBypassedWithoutChangingTheAuraFlowSamplingNode() {
        val settings = settings().copy(slg = MossSlgSettings(enabled = false))
        val workflow = MossWorkflow.build(settings)

        assertFalse(workflow.has("5"))
        assertEquals("ModelSamplingAuraFlow", workflow.getJSONObject("4").getString("class_type"))
        assertLink(workflow, "10", "model", "4")
        assertFalse(MossWorkflow.requiredNodeTypes(settings).contains("SkipLayerGuidanceDiTSimple"))
    }

    @Test
    fun rejectsBlankPromptAndInvalidDimensionsOrSamplingValues() {
        assertThrows(IllegalArgumentException::class.java) { settings().copy(prompt = " ").validate() }
        assertThrows(IllegalArgumentException::class.java) { settings().copy(width = 1023).validate() }
        assertThrows(IllegalArgumentException::class.java) { settings().copy(width = 2064).validate() }
        assertThrows(IllegalArgumentException::class.java) { settings().copy(width = 512, height = 256).validate() }
        assertThrows(IllegalArgumentException::class.java) { settings().copy(seed = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { settings().copy(steps = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { settings().copy(cfg = Double.NaN).validate() }
        assertThrows(IllegalArgumentException::class.java) { settings().copy(sampler = "").validate() }
    }

    @Test
    fun rejectsLayoutCoordinateOrderRangeAndMissingText() {
        assertThrows(IllegalArgumentException::class.java) {
            layoutSettings(1024, 1024).copy(
                layout = MossLayout(items = listOf(layoutItem().copy(left = 0.8, right = 0.2)))
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            layoutSettings(1024, 1024).copy(
                layout = MossLayout(items = listOf(layoutItem().copy(top = -0.1)))
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            layoutSettings(1024, 1024).copy(
                layout = MossLayout(items = listOf(layoutItem().copy(bottom = Double.NaN)))
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            layoutSettings(1024, 1024).copy(
                layout = MossLayout(items = listOf(layoutItem().copy(text = "")))
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings().copy(inputMode = MossInputMode.Layout).validate()
        }
    }
}
