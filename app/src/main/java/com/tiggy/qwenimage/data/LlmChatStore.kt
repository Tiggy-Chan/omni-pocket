package com.tiggy.qwenimage.data

import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class LlmChatSnapshot(
    val conversations: List<LlmConversation>,
    val selectedId: String?,
    val drafts: Map<String, String> = emptyMap()
)

class LlmChatStore(context: Context) {
    private val file = File(context.filesDir, "llm/conversations.json")

    fun load(): LlmChatSnapshot {
        if (!file.isFile) return LlmChatSnapshot(emptyList(), null)
        require(file.length() <= MAX_ARCHIVE_BYTES) { "会话存档过大，无法安全读取" }
        val restored = LlmChatArchive.parse(file.readText())
        val recovered = restored.copy(conversations = restored.conversations.map { conversation ->
            conversation.copy(messages = conversation.messages.map { message ->
                if (message.status == LlmMessageStatus.Streaming) {
                    message.copy(status = LlmMessageStatus.Interrupted)
                } else message
            })
        })
        if (recovered != restored) save(recovered)
        return recovered
    }

    fun save(snapshot: LlmChatSnapshot) {
        val parent = file.parentFile ?: error("无法确定会话目录")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建会话目录" }
        val temporary = File(parent, "conversations.json.tmp")
        try {
            val content = LlmChatArchive.serialize(snapshot)
            require(content.toByteArray(Charsets.UTF_8).size <= MAX_ARCHIVE_BYTES) { "会话存档超过 8MB，请整理旧对话" }
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, file.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    fun preserveUnreadable() {
        if (!file.exists()) return
        var backup = File(file.parentFile, "conversations.corrupt-${System.currentTimeMillis()}.json")
        var suffix = 1
        while (backup.exists()) backup = File(file.parentFile, "conversations.corrupt-${System.currentTimeMillis()}-${suffix++}.json")
        Os.rename(file.absolutePath, backup.absolutePath)
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 8_000_000
    }
}

object LlmChatArchive {
    fun parse(text: String): LlmChatSnapshot {
        val root = JSONObject(text)
        require(root.optInt("schemaVersion") == 1) { "不支持的会话存档版本" }
        val array = root.optJSONArray("conversations") ?: JSONArray()
        require(array.length() <= 100) { "会话数量超过上限" }
        val conversations = (0 until array.length()).map { LlmConversation.fromJson(array.getJSONObject(it)) }
        require(conversations.map { it.id }.distinct().size == conversations.size) { "会话 ID 重复" }
        val selected = root.optString("selectedId").takeIf { id -> conversations.any { it.id == id } }
        val draftObject = root.optJSONObject("drafts")
        val draftKeys = draftObject?.keys()?.asSequence()?.toList().orEmpty()
        require(draftKeys.size <= 100) { "草稿数量超过上限" }
        val drafts = draftKeys.mapNotNull { id ->
            val draft = draftObject?.getString(id).orEmpty()
            require(draft.length <= 8_000) { "草稿内容超过上限" }
            if (conversations.any { it.id == id }) id to draft else null
        }.toMap()
        return LlmChatSnapshot(conversations, selected ?: conversations.firstOrNull()?.id, drafts)
    }

    fun serialize(snapshot: LlmChatSnapshot): String = JSONObject()
        .put("schemaVersion", 1)
        .put("selectedId", snapshot.selectedId)
        .put("conversations", JSONArray().apply { snapshot.conversations.forEach { put(it.toJson()) } })
        .put("drafts", JSONObject().apply {
            snapshot.drafts.filterKeys { id -> snapshot.conversations.any { it.id == id } }
                .filterValues { it.isNotBlank() }.forEach { put(it.key, it.value) }
        })
        .toString()
}
