package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.DocumentFormat
import com.tiggy.qwenimage.data.ExtractedDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSummaryPipelineTest {

    @Test
    fun splitsLongTextRespectingParagraphs() {
        val para = "这是一段用于测试端侧分块阅读摘要的段落内容。".repeat(25)
        val text = (1..6).joinToString("\n\n") { "第${it}段：$para" }
        val chunks = DocumentSummaryPipeline.splitIntoChunks(text, maxChunkChars = 1_400, overlapChars = 100)
        assertTrue("Expected multiple chunks, got ${chunks.size}", chunks.size >= 3)
        assertTrue(chunks.all { it.length <= 1_400 })
        assertTrue(chunks.first().contains("第1段"))
        assertTrue(chunks.last().contains("第6段"))
    }

    @Test
    fun runsSinglePassForShortDocument() = runBlocking {
        val doc = ExtractedDocument("short.md", DocumentFormat.Markdown, "Short local note about privacy.", 32)
        val stages = mutableListOf<DocumentSummaryProgress>()
        var calls = 0
        val result = DocumentSummaryPipeline.execute(
            doc = doc,
            action = DocumentQuickAction.Summarize,
            userQuestion = "",
            contextTokens = 4096,
            maxNewTokens = 512,
            isCancelled = { false },
            onProgress = { stages.add(it) }
        ) { prompt, _, stream ->
            calls++
            assertTrue(stream)
            assertTrue(prompt.contains("short.md"))
            NativeLlmResult(0, "Summary ok", "", 40, 12, 0, 1000, 2000)
        }
        assertEquals(1, calls)
        assertEquals(listOf(DocumentSummaryProgress(DocumentSummaryStage.SinglePass, 1, 1)), stages)
        assertEquals("Summary ok", result.text)
    }

    @Test
    fun runsMapReduceForLongDocumentWithTruthfulProgress() = runBlocking {
        val longBody = (1..20).joinToString("\n\n") { idx ->
            "Section $idx: " + "On-device inference improves latency and privacy. ".repeat(12)
        }
        val doc = ExtractedDocument("long.txt", DocumentFormat.PlainText, longBody, longBody.length.toLong())
        val stages = mutableListOf<DocumentSummaryProgress>()
        val streamedFlags = mutableListOf<Boolean>()
        val result = DocumentSummaryPipeline.execute(
            doc = doc,
            action = DocumentQuickAction.KeyPoints,
            userQuestion = "",
            contextTokens = 2048,
            maxNewTokens = 512,
            isCancelled = { false },
            onProgress = { stages.add(it) }
        ) { _, _, stream ->
            streamedFlags.add(stream)
            NativeLlmResult(0, if (stream) "Final merged key points" else "Chunk note", "", 100, 30, 0, 500, 1000)
        }
        assertTrue(stages.size >= 3)
        assertEquals(DocumentSummaryStage.MapChunk, stages.first().stage)
        assertEquals(DocumentSummaryStage.ReduceFinal, stages.last().stage)
        // Only the final reduce step streams live to the user
        assertTrue(streamedFlags.dropLast(1).all { !it })
        assertTrue(streamedFlags.last())
        assertEquals("Final merged key points", result.text)
        assertTrue(result.promptTokens >= 300)
    }
}
