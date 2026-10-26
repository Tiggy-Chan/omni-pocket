package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject

data class MossModelArtifact(
    val path: String,
    val size: Long,
    val sha256: String,
    val url: String
)

data class MossModelManifest(
    val modelId: String,
    val artifacts: List<MossModelArtifact>
) {
    fun toJson() = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("modelId", modelId)
        put("files", JSONArray().apply {
            artifacts.forEach {
                put(JSONObject()
                    .put("path", it.path)
                    .put("size", it.size)
                    .put("sha256", it.sha256)
                    .put("url", it.url))
            }
        })
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MODEL_ID = "moss-image-local-v1"

        val FILES = listOf(
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
        )

        fun contains(path: String) = FILES.any { it.path == path }

        fun validPath(path: String) = path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." } && path.none { it.code < 32 }

        fun fromJson(json: JSONObject): MossModelManifest {
            require(json.getInt("schemaVersion") == SCHEMA_VERSION) { "不支持的 MOSS 模型清单版本" }
            val modelId = json.getString("modelId")
            require(modelId == MODEL_ID) { "模型清单不是 $MODEL_ID" }
            val files = json.getJSONArray("files")
            require(files.length() == FILES.size) { "MOSS 模型清单必须包含四个文件" }
            val artifacts = (0 until files.length()).map { index ->
                val item = files.getJSONObject(index)
                val path = item.getString("path")
                val size = item.getLong("size")
                val sha256 = item.getString("sha256").lowercase()
                val url = item.getString("url")
                require(validPath(path)) { "非法模型路径：$path" }
                require(size > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) { "模型清单必须包含大小和真实 SHA-256" }
                require(url.matches(Regex("https://\\S+"))) { "模型文件必须使用有效的 HTTPS 来源" }
                MossModelArtifact(path, size, sha256, url)
            }
            require(artifacts.map { it.path }.distinct().size == artifacts.size) { "清单中出现重复文件" }
            val actualByPath = artifacts.associateBy { it.path }
            require(actualByPath.keys == FILES.map { it.path }.toSet()) { "MOSS 模型清单缺少文件或包含未知文件" }
            FILES.forEach { expected ->
                val actual = actualByPath.getValue(expected.path)
                require(actual.size == expected.size) { "${expected.path} 大小与内置清单不符" }
                require(actual.sha256 == expected.sha256) { "${expected.path} SHA-256 与内置清单不符" }
                require(actual.url == expected.url) { "${expected.path} 来源 URL 与内置清单不符" }
            }
            return MossModelManifest(modelId, artifacts)
        }
    }
}
