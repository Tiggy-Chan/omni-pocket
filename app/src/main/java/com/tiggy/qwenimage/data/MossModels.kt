package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

const val MOSS_TEXT_ENCODER = "qwen3.5_9b_w4a8_gptq.safetensors"
const val MOSS_VAE = "flux2-vae_small.safetensors"
const val DEFAULT_MOSS_NEGATIVE_PROMPT = "nsfw, worst quality, bad anatomy, watermark, jpeg artifact"

enum class MossCheckpoint(val fileName: String) {
    Sft("moss_image_sft_int8_convrot.safetensors"),
    Base("moss_image_base_int8_convrot.safetensors")
}

enum class ImageGenerationBackend { QwenLocal, MossLocal, MossWorkstation }

enum class MossInputMode { Prompt, Layout }

enum class MossLayoutLabel(val wireValue: String) {
    Text("text"),
    Object("object")
}

data class MossLayoutItem(
    val label: MossLayoutLabel,
    val caption: String,
    val text: String = "",
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    fun validate() {
        require(caption.isNotBlank()) { "布局元素描述不能为空" }
        require(label != MossLayoutLabel.Text || text.isNotBlank()) { "文字布局元素必须包含文字" }
        require(listOf(left, top, right, bottom).all { it.isFinite() }) { "布局坐标必须是有限数值" }
        require(left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0) {
            "布局坐标必须位于 0 到 1 之间"
        }
        require(left < right && top < bottom) { "布局框必须满足 left < right、top < bottom" }
    }

    fun toJson() = JSONObject().apply {
        put("label", label.wireValue)
        put("caption", caption)
        if (label == MossLayoutLabel.Text) put("text", text)
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
    }

    internal fun toModelJson(width: Int, height: Int): JSONObject {
        validate()
        val topEdge = (top * height - height / 2.0).roundToInt()
        val leftEdge = (left * width - width / 2.0).roundToInt()
        val bottomEdge = (bottom * height - height / 2.0).roundToInt()
        val rightEdge = (right * width - width / 2.0).roundToInt()
        require(topEdge < bottomEdge && leftEdge < rightEdge) { "布局框换算为像素后不能为空" }
        return JSONObject().apply {
            put("label", label.wireValue)
            put("caption", caption)
            if (label == MossLayoutLabel.Text) put("text", text)
            put("bbox", JSONArray().put(topEdge).put(leftEdge).put(bottomEdge).put(rightEdge))
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = MossLayoutItem(
            label = MossLayoutLabel.values().firstOrNull { it.wireValue == o.optString("label") } ?: MossLayoutLabel.Object,
            caption = o.optString("caption"),
            text = o.optString("text"),
            left = o.optDouble("left"),
            top = o.optDouble("top"),
            right = o.optDouble("right"),
            bottom = o.optDouble("bottom")
        )
    }
}

data class MossLayout(
    val meta: List<String> = listOf("poster"),
    val canvas: String = "single",
    val items: List<MossLayoutItem> = emptyList()
) {
    fun validate() {
        require(meta.isNotEmpty() && meta.all { it.isNotBlank() }) { "布局 meta 不能为空" }
        require(canvas.isNotBlank()) { "布局 canvas 不能为空" }
        require(items.isNotEmpty()) { "布局模式至少需要一个元素" }
        require(items.size <= 256) { "布局元素不能超过 256 个" }
        items.forEach { it.validate() }
    }

    fun toJson() = JSONObject().apply {
        put("meta", JSONArray().apply { meta.forEach { put(it) } })
        put("canvas", canvas)
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
    }

    internal fun toModelJson(caption: String, width: Int, height: Int): JSONObject {
        require(caption.isNotBlank()) { "请输入布局画面描述" }
        require(width > 0 && height > 0) { "布局画布尺寸必须为正数" }
        validate()
        return JSONObject().apply {
            put("summary", JSONObject().apply {
                put("meta", JSONArray().apply { meta.forEach { put(it) } })
                put("canvas", canvas)
                put("caption", caption)
            })
            put("items", JSONArray().apply { items.forEach { put(it.toModelJson(width, height)) } })
            put("bbox_coord_space", "abs_centralized")
            put("bbox_format", "yxyx")
        }
    }

    companion object {
        fun fromJson(o: JSONObject): MossLayout {
            val metaArray = o.optJSONArray("meta")
            val itemArray = o.optJSONArray("items")
            return MossLayout(
                meta = if (metaArray == null) listOf("poster") else (0 until metaArray.length()).map { metaArray.getString(it) },
                canvas = o.optString("canvas", "single"),
                items = if (itemArray == null) emptyList() else (0 until itemArray.length()).map {
                    MossLayoutItem.fromJson(itemArray.getJSONObject(it))
                }
            )
        }
    }
}

data class MossSlgSettings(
    val enabled: Boolean = true,
    val singleLayers: String = "22",
    val startPercent: Double = 0.0,
    val endPercent: Double = 0.5
) {
    fun validate() {
        require(startPercent.isFinite() && endPercent.isFinite()) { "SLG 范围必须是有限数值" }
        require(startPercent in 0.0..1.0 && endPercent in 0.0..1.0 && startPercent < endPercent) {
            "SLG 范围必须满足 0 ≤ start < end ≤ 1"
        }
        if (enabled) {
            require(singleLayers.matches(Regex("\\s*\\d+(\\s*,\\s*\\d+)*\\s*"))) {
                "SLG singleLayers 必须是逗号分隔的非负层号"
            }
        }
    }

    fun toJson() = JSONObject().apply {
        put("enabled", enabled)
        put("singleLayers", singleLayers)
        put("startPercent", startPercent)
        put("endPercent", endPercent)
    }

    companion object {
        fun fromJson(o: JSONObject) = MossSlgSettings(
            enabled = o.optBoolean("enabled", true),
            singleLayers = o.optString("singleLayers", "22"),
            startPercent = o.optDouble("startPercent", 0.0),
            endPercent = o.optDouble("endPercent", 0.5)
        )
    }
}

data class MossSettings(
    val serverUrl: String = "http://127.0.0.1:8188",
    val checkpoint: MossCheckpoint = MossCheckpoint.Sft,
    val prompt: String = "",
    val negativePrompt: String = DEFAULT_MOSS_NEGATIVE_PROMPT,
    val layout: MossLayout = MossLayout(),
    val width: Int = 1024,
    val height: Int = 1024,
    val seed: Long = 42L,
    val steps: Int = 25,
    val cfg: Double = 2.5,
    val shift: Double = 5.0,
    val sampler: String = "er_sde",
    val scheduler: String = "simple",
    val slg: MossSlgSettings = MossSlgSettings(),
    val promptUpsampler: Boolean = true,
    val inputMode: MossInputMode = MossInputMode.Prompt,
    val local: MossLocalSettings = MossLocalSettings()
) {
    val usesPromptUpsampler: Boolean
        get() = promptUpsampler && inputMode == MossInputMode.Prompt

    fun modelPrompt(): String = if (inputMode == MossInputMode.Layout) {
        layout.toModelJson(prompt, width, height).toString()
    } else {
        prompt
    }

    fun localModelPrompt(): String = if (inputMode == MossInputMode.Layout) {
        layout.toModelJson(prompt, local.width, local.height).toString()
    } else {
        prompt
    }

    fun validate() {
        require(serverUrl.isNotBlank()) { "请输入 ComfyUI 工作站地址" }
        validateCommon(width, height, cfg)
        require(steps in 1..200) { "采样步数必须为 1–200" }
        require(sampler.isNotBlank()) { "sampler 不能为空" }
        require(scheduler.isNotBlank()) { "scheduler 不能为空" }
        slg.validate()
    }

    fun validateLocal() {
        validateCommon(local.width, local.height, local.cfg)
        require(checkpoint == MossCheckpoint.Sft) { "当前本机模型包提供 SFT；Base 可使用工作站" }
        local.validate()
        require(local.width.toLong() * local.height.toLong() <= 1_048_576L) { "本机 MOSS 当前最多输出 1 MP" }
    }

    private fun validateCommon(width: Int, height: Int, cfg: Double) {
        require(prompt.isNotBlank()) { "请输入提示词" }
        require(width % 16 == 0 && height % 16 == 0) { "MOSS 宽高必须为 16 的倍数" }
        require(width > 0 && height > 0 && width <= 2048 && height <= 2048) { "MOSS 宽高不能超过 2048" }
        val pixels = width.toLong() * height.toLong()
        require(pixels in 262_144L..4_194_304L) { "MOSS 总像素数必须在 0.25 MP 到 4 MP 之间" }
        require(seed >= 0) { "seed 不能为负数" }
        require(cfg.isFinite() && cfg in 0.0..20.0) { "CFG 必须位于 0 到 20 之间" }
        require(shift.isFinite() && shift in 0.0..100.0) { "shift 必须位于 0 到 100 之间" }
        if (inputMode == MossInputMode.Layout) layout.toModelJson(prompt, width, height)
    }

    fun toJson() = JSONObject().apply {
        put("serverUrl", serverUrl)
        put("checkpoint", checkpoint.name)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("layout", layout.toJson())
        put("width", width)
        put("height", height)
        put("seed", seed)
        put("steps", steps)
        put("cfg", cfg)
        put("shift", shift)
        put("sampler", sampler)
        put("scheduler", scheduler)
        put("slg", slg.toJson())
        put("promptUpsampler", promptUpsampler)
        put("inputMode", inputMode.name)
        put("local", local.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): MossSettings {
            val checkpointValue = o.optString("checkpoint", MossCheckpoint.Sft.name)
            return MossSettings(
                serverUrl = o.optString("serverUrl", "http://127.0.0.1:8188"),
                checkpoint = MossCheckpoint.values().firstOrNull {
                    it.name.equals(checkpointValue, ignoreCase = true) || it.fileName == checkpointValue
                } ?: MossCheckpoint.Sft,
                prompt = o.optString("prompt"),
                negativePrompt = o.optString("negativePrompt", DEFAULT_MOSS_NEGATIVE_PROMPT),
                layout = MossLayout.fromJson(o.optJSONObject("layout") ?: JSONObject()),
                width = o.optInt("width", 1024),
                height = o.optInt("height", 1024),
                seed = o.optLong("seed", 42L),
                steps = o.optInt("steps", 25),
                cfg = o.optDouble("cfg", 2.5),
                shift = o.optDouble("shift", 5.0),
                sampler = o.optString("sampler", "er_sde"),
                scheduler = o.optString("scheduler", "simple"),
                slg = MossSlgSettings.fromJson(o.optJSONObject("slg") ?: JSONObject()),
                promptUpsampler = o.optBoolean("promptUpsampler", true),
                inputMode = runCatching { MossInputMode.valueOf(o.optString("inputMode")) }
                    .getOrDefault(MossInputMode.Prompt),
                local = MossLocalSettings.fromJson(o.optJSONObject("local"))
            )
        }
    }
}

data class MossGenerationResult(
    val file: File,
    val width: Int,
    val height: Int,
    val promptId: String
)
