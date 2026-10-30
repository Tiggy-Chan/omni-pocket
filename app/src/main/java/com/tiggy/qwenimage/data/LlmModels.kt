package com.tiggy.qwenimage.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LlmModelArtifact(val path: String, val size: Long, val sha256: String, val url: String)

enum class LlmModelKind(val json: String) {
    Chat("chat"),
    Decision("decision")
}

data class LlmModelSpec(
    val id: String,
    val name: String,
    val repo: String,
    val revision: String,
    val artifacts: List<LlmModelArtifact>,
    val kind: LlmModelKind = LlmModelKind.Chat,
    val localOnly: Boolean = kind == LlmModelKind.Decision
) {
    val totalBytes: Long get() = artifacts.sumOf { it.size }

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("family", "qwen3_5")
        put("kind", kind.json)
        put("repo", repo)
        put("revision", revision)
        put("localOnly", localOnly)
        put("files", JSONArray().apply {
            artifacts.forEach { artifact ->
                put(JSONObject().put("path", artifact.path).put("size", artifact.size)
                    .put("sha256", artifact.sha256).put("url", artifact.url))
            }
        })
    }

    companion object {
        val REQUIRED_FILES = listOf(
            "config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight",
            "tokenizer.txt", "visual.mnn", "visual.mnn.weight"
        )
        const val DECISION_WEIGHTS = "elephant-1.Q4_K_M.gguf"
        val DECISION_REQUIRED_FILES = listOf(
            "config.json", "llm_config.json", "decision.json", "provenance.json", DECISION_WEIGHTS
        )

        fun fromJson(json: JSONObject): LlmModelSpec {
            val kind = when (json.optString("kind", LlmModelKind.Chat.json)) {
                LlmModelKind.Chat.json -> LlmModelKind.Chat
                LlmModelKind.Decision.json -> LlmModelKind.Decision
                else -> throw IllegalArgumentException("不支持的模型类型")
            }
            val id = json.getString("id")
            val idValid = when (kind) {
                LlmModelKind.Chat -> id.matches(Regex("qwen35-(0\\.8b|2b|4b)"))
                LlmModelKind.Decision -> id == "elephant-1"
            }
            require(idValid) { "模型 ID 与类型不匹配" }
            require(json.optString("family", "qwen3_5") == "qwen3_5") { "模型架构不是 qwen3_5" }
            val name = json.getString("name")
            val repo = json.getString("repo")
            val revision = json.getString("revision")
            val localOnly = json.optBoolean("localOnly", kind == LlmModelKind.Decision)
            require(localOnly == (kind == LlmModelKind.Decision)) { "模型本地导入属性无效" }
            require(name.isNotBlank() && name.length <= 80) { "模型名称无效" }
            require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "模型来源无效" }
            require(revision.matches(Regex("[0-9a-f]{40}"))) { "模型必须固定到完整提交哈希" }
            val requiredFiles = if (kind == LlmModelKind.Decision) DECISION_REQUIRED_FILES else REQUIRED_FILES
            val files = json.getJSONArray("files")
            require(files.length() == requiredFiles.size) { "LLM 清单文件数量错误" }
            val artifacts = (0 until files.length()).map { index ->
                val file = files.getJSONObject(index)
                val path = file.getString("path")
                val sha256 = file.getString("sha256").lowercase()
                val url = file.getString("url")
                require(path in requiredFiles) { "未知 LLM 文件：$path" }
                require(file.getLong("size") > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) {
                    "模型文件缺少有效大小或 SHA-256"
                }
                val expectedUrl = if (localOnly) "" else "https://huggingface.co/$repo/resolve/$revision/$path"
                require(url == expectedUrl) { "模型 URL 与固定来源不一致" }
                LlmModelArtifact(path, file.getLong("size"), sha256, url)
            }
            require(artifacts.map { it.path }.toSet() == requiredFiles.toSet()) { "LLM 文件不完整" }
            return LlmModelSpec(id, name, repo, revision, artifacts, kind, localOnly)
        }
    }
}

data class LlmModelStatus(
    val spec: LlmModelSpec,
    val directory: File,
    val present: Int,
    val ready: Boolean,
    val message: String
)

object LlmCatalog {
    fun parse(text: String): List<LlmModelSpec> {
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == 1) { "不支持的 LLM 清单版本" }
        val models = json.getJSONArray("models")
        require(models.length() in 1..4) { "LLM 清单数量异常" }
        return (0 until models.length()).map { LlmModelSpec.fromJson(models.getJSONObject(it)) }.also {
            require(it.map(LlmModelSpec::id).distinct().size == it.size) { "LLM 清单出现重复模型" }
        }
    }

    fun manifest(spec: LlmModelSpec) = JSONObject().put("schemaVersion", 1)
        .put("models", JSONArray().put(spec.toJson())).toString()
}

enum class LlmMessageStatus { Complete, Streaming, Cancelled, Interrupted, MaxTokens, Failed }

data class LlmMessage(
    val id: String,
    val role: String,
    val content: String,
    val reasoning: String = "",
    val status: LlmMessageStatus = LlmMessageStatus.Complete,
    val createdAt: Long = System.currentTimeMillis()
) {
    init { require(role == "user" || role == "assistant") { "不支持的对话角色" } }

    fun contextContent(): String = if (role == "assistant") content else content

    fun toJson() = JSONObject().apply {
        put("id", id); put("role", role); put("content", content); put("reasoning", reasoning)
        put("status", status.name); put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = LlmMessage(
            id = json.getString("id"), role = json.getString("role"), content = json.getString("content"),
            reasoning = json.optString("reasoning"),
            status = runCatching { LlmMessageStatus.valueOf(json.optString("status")) }
                .getOrDefault(LlmMessageStatus.Complete),
            createdAt = json.optLong("createdAt")
        )
    }
}

data class LlmConversation(
    val id: String,
    val title: String,
    val modelId: String,
    val thinking: Boolean = false,
    val messages: List<LlmMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "会话 ID 无效" }
        require(title.length <= 120 && modelId.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "会话信息无效" }
        require(messages.size <= 500 && messages.all { it.content.length + it.reasoning.length <= 1_000_000 }) {
            "会话内容超出存储上限"
        }
    }

    fun modelMessages(): List<Pair<String, String>> = buildList {
        add("system" to SYSTEM_PROMPT)
        val usable = messages.filter { it.status != LlmMessageStatus.Failed && it.content.isNotBlank() }
        usable.forEachIndexed { index, message ->
            if (message.role == "user") {
                val answer = usable.getOrNull(index + 1)
                if (answer?.role == "assistant") {
                    add("user" to message.content)
                    add("assistant" to answer.contextContent())
                } else if (index == usable.lastIndex) {
                    add("user" to message.content)
                }
            }
        }
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("modelId", modelId); put("thinking", thinking)
        put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
    }

    companion object {
        const val SYSTEM_PROMPT = "You are a helpful assistant. Answer in the user's language. Be accurate and concise."

        fun fromJson(json: JSONObject) = LlmConversation(
            id = json.getString("id"), title = json.optString("title", "新对话"),
            modelId = json.getString("modelId"), thinking = json.optBoolean("thinking"),
            messages = (json.optJSONArray("messages") ?: JSONArray()).let { array ->
                (0 until array.length()).map { LlmMessage.fromJson(array.getJSONObject(it)) }
            },
            createdAt = json.optLong("createdAt"), updatedAt = json.optLong("updatedAt")
        )
    }
}

fun splitReasoning(raw: String, thinking: Boolean = false): Pair<String, String> {
    val marker = raw.indexOf("<think>")
    if (marker < 0 && !thinking) return raw to ""
    val tagStart = if (marker >= 0) marker else 0
    val reasoningStart = if (marker >= 0) marker + 7 else 0
    val end = raw.indexOf("</think>", reasoningStart)
    val reasoning = raw.substring(reasoningStart, if (end >= 0) end else raw.length).trim()
    val content = if (end >= 0) raw.removeRange(tagStart, end + 8) else raw.substring(0, tagStart)
    return content.trim() to reasoning
}
