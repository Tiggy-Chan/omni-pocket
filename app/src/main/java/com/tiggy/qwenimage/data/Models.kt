package com.tiggy.qwenimage.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// This runtime implements Euler flow matching with CFG=1; unsupported samplers are not exposed.
enum class WorkspaceTab { Generate, Queue, Models, Settings }
enum class GenerationMode { TextToImage, ImageEdit }

data class GenerationSettings(
    val mode: GenerationMode = GenerationMode.TextToImage,
    val prompt: String = "",
    val steps: Int = 20,
    val seed: Int = 42,
    val width: Int = 512,
    val height: Int = 512,
    val turbo: Boolean = false,
    val useTinyVae: Boolean = true,
    val secondReference: Boolean = false,
    val transparent: Boolean = false,
    val inputUri: Uri? = null,
    val inputUri2: Uri? = null
) {
    fun validate() {
        require(prompt.isNotBlank()) { "请输入提示词" }
        require(prompt.length <= 8000) { "提示词最多 8000 个字符" }
        require(width in 128..1024 && height in 128..1024 && width % 32 == 0 && height % 32 == 0) { "尺寸必须为 128–1024 内的 32 倍数" }
        require(steps in 1..40) { "步数必须为 1–40" }
        require(mode != GenerationMode.ImageEdit || inputUri != null) { "图片编辑需要参考图" }
    }
    fun toJson() = JSONObject().apply {
        put("mode", mode.name); put("prompt", prompt); put("steps", steps); put("seed", seed)
        put("width", width); put("height", height); put("turbo", turbo); put("tinyVae", useTinyVae)
        put("halfReferences", secondReference); put("transparent", transparent)
        put("input", inputUri?.toString()); put("input2", inputUri2?.toString())
    }
    companion object {
        fun fromJson(o: JSONObject) = GenerationSettings(
            mode = GenerationMode.valueOf(o.optString("mode", "TextToImage")),
            prompt = o.optString("prompt"), steps = o.optInt("steps", 20), seed = o.optInt("seed", 42),
            width = o.optInt("width", 512), height = o.optInt("height", 512),
            turbo = o.optBoolean("turbo"), useTinyVae = o.optBoolean("tinyVae", true),
            secondReference = o.optBoolean("halfReferences"), transparent = o.optBoolean("transparent"),
            inputUri = o.optString("input").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse),
            inputUri2 = o.optString("input2").takeIf { it.isNotBlank() && it != "null" }?.let(Uri::parse)
        )
    }
}

data class GenerationJob(
    val id: String,
    val settings: GenerationSettings,
    val state: String = "queued",
    val progress: Int = 0,
    val outputPath: String? = null,
    val error: String? = null,
    val elapsedMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("settings", settings.toJson()); put("state", state); put("progress", progress)
        put("output", outputPath); put("error", error); put("elapsedMs", elapsedMs); put("createdAt", createdAt)
    }
    companion object {
        fun fromJson(o: JSONObject) = GenerationJob(
            o.getString("id"), GenerationSettings.fromJson(o.getJSONObject("settings")), o.getString("state"),
            o.optInt("progress"), o.optString("output").takeIf { it.isNotBlank() && it != "null" },
            o.optString("error").takeIf { it.isNotBlank() && it != "null" }, o.optLong("elapsedMs"), o.optLong("createdAt")
        )
    }
}

data class ModelArtifact(val path: String, val size: Long, val sha256: String)

data class ModelManifest(
    val modelId: String,
    val sourceRepo: String,
    val revision: String,
    val artifacts: List<ModelArtifact>,
    val features: Set<String> = emptySet()
) {
    fun toJson() = JSONObject().apply {
        put("schemaVersion", 1); put("modelId", modelId); put("sourceRepo", sourceRepo); put("revision", revision)
        put("features", JSONArray(features.toList()))
        put("files", JSONArray().apply {
            artifacts.forEach { put(JSONObject().put("path", it.path).put("size", it.size).put("sha256", it.sha256)) }
        })
    }
    companion object {
        const val DEFAULT_REPO = ""
        const val DEFAULT_REVISION = "main"
        val SHARED_FILES = listOf(
            "img_in.mnn", "img_in.mnn.weight", "txt_in.mnn", "txt_in.mnn.weight",
            "text_encoder/llm.mnn", "text_encoder/llm.mnn.weight", "text_encoder/embeddings_int4.bin",
            "text_encoder/tokenizer.txt", "text_encoder/llm_config.json", "text_encoder/te_config.json", "text_encoder/te_llm_config.json"
        )
        val EDIT_FILES = listOf("text_encoder/visual.mnn", "text_encoder/visual.mnn.weight", "text_encoder/te_vl_config.json", "text_encoder/te_vl_llm_config.json")
        fun empty() = ModelManifest("qwen-image-2.1-uc-q4km", "abenzerps/Qwen-Image-2.1-Uncensored-GGUF", "", emptyList())
        fun validPath(path: String) = path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." } && path.none { it.code < 32 }
        fun fromJson(json: JSONObject): ModelManifest {
            require(json.getInt("schemaVersion") == 1) { "不支持的模型清单版本" }
            require(json.getString("modelId") == "qwen-image-2.1-uc-q4km") { "请选择目标 UC Q4_K_M 转换包" }
            val files = json.getJSONArray("files")
            require(files.length() in 1..100) { "模型清单文件数量异常" }
            val artifacts = (0 until files.length()).map {
                val item = files.getJSONObject(it)
                val path = item.getString("path")
                val hash = item.getString("sha256").lowercase()
                val size = item.getLong("size")
                require(validPath(path) && path != "manifest.json") { "非法模型路径：$path" }
                require(hash.matches(Regex("[0-9a-f]{64}")) && size > 0) { "模型清单必须包含大小和 SHA-256" }
                ModelArtifact(path, size, hash)
            }
            require(artifacts.map { it.path }.distinct().size == artifacts.size) { "清单中出现重复文件" }
            val featuresArray = json.getJSONArray("features")
            val features = (0 until featuresArray.length()).map { featuresArray.getString(it) }.toSet()
            val paths = artifacts.map { it.path }.toSet()
            require(paths.containsAll(SHARED_FILES)) { "缺少共享模型组件" }
            require(paths.containsAll(listOf("dit.mnn", "dit.mnn.weight"))) { "缺少 DiT 基础模型" }
            require(paths.containsAll(listOf("vae_decoder_tiny.mnn", "vae_encoder_tiny.mnn"))) { "缺少 Tiny VAE" }
            if ("image-edit" in features) require(paths.containsAll(EDIT_FILES)) { "缺少视觉编码器" }
            if ("turbo" in features) {
                require(json.optBoolean("turboValidated", false)) { "Turbo 尚未通过回归验证" }
                require(paths.containsAll(listOf("dit_turbo.mnn", "dit_turbo.mnn.weight"))) { "缺少 Turbo 模型" }
            }
            require("text-to-image" in features) { "模型包未声明文生图能力" }
            return ModelManifest(json.getString("modelId"), json.getString("sourceRepo"), json.getString("revision"), artifacts, features)
        }
    }
}

data class ModelStatus(val directory: File, val manifest: ModelManifest, val present: Int, val total: Int, val ready: Boolean, val message: String)
