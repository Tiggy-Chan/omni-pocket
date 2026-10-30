package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmChatArchiveTest {
    @Test
    fun archiveRetainsConversationSelectionThinkingAndReasoning() {
        val conversation = LlmConversation(
            id = "one", title = "水", modelId = "qwen35-0.8b", thinking = true,
            messages = listOf(
                LlmMessage("user-1", "user", "水的化学式"),
                LlmMessage("answer-1", "assistant", "H₂O", reasoning = "用户询问化学式")
            )
        )
        val snapshot = LlmChatSnapshot(listOf(conversation), "one", mapOf("one" to "尚未发送的草稿"))
        val restored = LlmChatArchive.parse(LlmChatArchive.serialize(snapshot))
        assertEquals(snapshot, restored)
    }

    @Test
    fun modelContextExcludesReasoningAndUnansweredEarlierTurns() {
        val conversation = LlmConversation(
            id = "one", title = "test", modelId = "qwen35-0.8b",
            messages = listOf(
                LlmMessage("u1", "user", "旧问题"),
                LlmMessage("a1", "assistant", "旧回答", reasoning = "不要重复发送思考过程"),
                LlmMessage("u2", "user", "失败的提问"),
                LlmMessage("a2", "assistant", "", status = LlmMessageStatus.Failed),
                LlmMessage("u3", "user", "当前问题"),
                LlmMessage("a3", "assistant", "", status = LlmMessageStatus.Streaming)
            )
        )
        assertEquals(listOf(
            "system" to LlmConversation.SYSTEM_PROMPT,
            "user" to "旧问题", "assistant" to "旧回答", "user" to "当前问题"
        ), conversation.modelMessages())
    }

    @Test
    fun parsesClosedAndUnclosedThinkBlocks() {
        assertEquals("答案" to "过程", splitReasoning("<think>过程</think>答案"))
        assertEquals("" to "仍在思考", splitReasoning("<think>仍在思考"))
        assertEquals("普通回答" to "", splitReasoning("普通回答"))
        assertEquals("4" to "先计算", splitReasoning("先计算</think>4", thinking = true))
        assertEquals("" to "尚未完成", splitReasoning("尚未完成", thinking = true))
    }
}
