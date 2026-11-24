package com.tiggy.qwenimage.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBaseSearchTest {

    @Test
    fun roundTripsFloat32VectorBlob() {
        val original = floatArrayOf(0.5f, -0.5f, 0.5f, 0.5f)
        val encoded = KnowledgeBaseSearch.encodeFloatArray(original)
        val decoded = KnowledgeBaseSearch.decodeFloatArray(encoded)!!
        assertArrayEquals(original, decoded, 1e-6f)
        assertEquals(1.0f, KnowledgeBaseSearch.cosineSimilarity(original, decoded), 1e-5f)
    }

    @Test
    fun ranksHybridVectorAndLexicalHitsAccurately() {
        val chunks = listOf(
            KnowledgeChunkRecord(
                id = "c1", kbId = "kb1", docId = "d1", docName = "gpu.md", chunkIndex = 0,
                text = "Adreno 750 OpenCL 后端支持 FP16 与 Q4_0 矩阵乘法加速。",
                embedding = floatArrayOf(0.95f, 0.1f, 0.0f)
            ),
            KnowledgeChunkRecord(
                id = "c2", kbId = "kb1", docId = "d1", docName = "privacy.md", chunkIndex = 0,
                text = "所有推理均在手机端本地离线完成，不上传任何用户隐私数据。",
                embedding = floatArrayOf(0.1f, 0.98f, 0.0f)
            ),
            KnowledgeChunkRecord(
                id = "c3", kbId = "kb1", docId = "d2", docName = "recipe.md", chunkIndex = 0,
                text = "番茄炒蛋需要准备两个鸡蛋和新鲜西红柿。",
                embedding = floatArrayOf(0.0f, 0.0f, 1.0f)
            )
        )
        val hits = KnowledgeBaseSearch.rankHybridHits(
            query = "手机本地离线隐私",
            queryEmbedding = floatArrayOf(0.12f, 0.96f, 0.0f),
            chunks = chunks,
            topK = 2
        )
        assertEquals(2, hits.size)
        assertEquals("c2", hits.first().chunkId)
        assertTrue(hits.first().score > hits[1].score)
    }

    @Test
    fun fallsBackToLexicalSearchWithoutEmbeddings() {
        val chunks = listOf(
            KnowledgeChunkRecord("a", "kb", "d1", "arch.md", 0, "Wan2.2 视频生成必须保持 vae=CPU 以避免花屏。"),
            KnowledgeChunkRecord("b", "kb", "d2", "music.md", 0, "YuE2-3B 音乐生成支持中文与英文歌词。")
        )
        val hits = KnowledgeBaseSearch.rankHybridHits(
            query = "为什么 Wan2.2 要设 vae=CPU",
            queryEmbedding = null,
            chunks = chunks,
            topK = 2
        )
        assertTrue(hits.isNotEmpty())
        assertEquals("a", hits.first().chunkId)
    }

    @Test
    fun formatsRagPromptWithNumberedCitations() {
        val hits = listOf(
            KnowledgeHit("c1", "d1", "guide.pdf", 2, "KleidiAI improves ARM NEON throughput.", 0.88f, 0.9f, 0.8f)
        )
        val prompt = KnowledgeBaseSearch.buildRagPrompt(hits, "How does KleidiAI help?")
        assertTrue(prompt.contains("[1] guide.pdf (#3)"))
        assertTrue(prompt.contains("KleidiAI improves ARM NEON throughput."))
        assertTrue(prompt.contains("How does KleidiAI help?"))
    }
}
