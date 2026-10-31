package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

const val DEFAULT_MOSS_NEGATIVE_PROMPT = "nsfw, worst quality, bad anatomy, watermark, jpeg artifact"

enum class ImageGenerationBackend { QwenLocal, MossLocal }

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

data class MossSettings(
    val prompt: String = "",
    val negativePrompt: String = DEFAULT_MOSS_NEGATIVE_PROMPT,
    val layout: MossLayout = MossLayout(),
    val seed: Long = 42L,
    val shift: Double = 5.0,
    val inputMode: MossInputMode = MossInputMode.Prompt,
    val local: MossLocalSettings = MossLocalSettings()
) {
    fun localModelPrompt(): String = if (inputMode == MossInputMode.Layout) {
        layout.toModelJson(prompt, local.width, local.height).toString()
    } else {
        prompt
    }

    fun validateLocal() {
        require(prompt.isNotBlank()) { "请输入提示词" }
        require(local.width % 16 == 0 && local.height % 16 == 0) { "MOSS 宽高必须为 16 的倍数" }
        require(local.width > 0 && local.height > 0) { "MOSS 宽高必须为正数" }
        val pixels = local.width.toLong() * local.height.toLong()
        require(pixels >= 262_144L) { "MOSS 总像素数不能低于 0.25 MP" }
        require(pixels <= 1_048_576L) { "本机 MOSS 当前最多输出 1 MP" }
        require(seed >= 0) { "seed 不能为负数" }
        require(shift.isFinite() && shift in 0.0..100.0) { "shift 必须位于 0 到 100 之间" }
        local.validate()
        if (inputMode == MossInputMode.Layout) layout.toModelJson(prompt, local.width, local.height)
    }

    fun toJson() = JSONObject().apply {
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("layout", layout.toJson())
        put("seed", seed)
        put("shift", shift)
        put("inputMode", inputMode.name)
        put("local", local.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = MossSettings(
            prompt = o.optString("prompt"),
            negativePrompt = o.optString("negativePrompt", DEFAULT_MOSS_NEGATIVE_PROMPT),
            layout = MossLayout.fromJson(o.optJSONObject("layout") ?: JSONObject()),
            seed = o.optLong("seed", 42L),
            shift = o.optDouble("shift", 5.0),
            inputMode = runCatching { MossInputMode.valueOf(o.optString("inputMode")) }
                .getOrDefault(MossInputMode.Prompt),
            local = MossLocalSettings.fromJson(o.optJSONObject("local"))
        )
    }
}

data class MossGenerationResult(
    val file: File,
    val width: Int,
    val height: Int
)
