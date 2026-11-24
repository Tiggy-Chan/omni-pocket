package com.tiggy.qwenimage.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

data class KnowledgeChunkRecord(
    val id: String,
    val kbId: String,
    val docId: String,
    val docName: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: FloatArray? = null,
    val embeddingModelId: String? = null
)

data class KnowledgeHit(
    val chunkId: String,
    val docId: String,
    val docName: String,
    val chunkIndex: Int,
    val text: String,
    val score: Float,
    val vectorScore: Float,
    val lexicalScore: Float
)

/**
 * Pure-Kotlin chunking, Float32 vector serialization, and hybrid cosine + lexical Top-K retrieval.
 * Designed for <10 ms recall over thousands of local chunks with zero native vector DB overhead.
 */
object KnowledgeBaseSearch {
    const val DEFAULT_CHUNK_CHARS = 650
    const val DEFAULT_OVERLAP_CHARS = 100
    const val MAX_CHUNKS_PER_DOC = 400

    fun splitDocumentForKnowledgeBase(
        text: String,
        maxChunkChars: Int = DEFAULT_CHUNK_CHARS,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS
    ): List<String> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val limit = maxChunkChars.coerceAtLeast(160)
        if (normalized.length <= limit) return listOf(normalized)

        val overlap = overlapChars.coerceIn(0, limit / 3)
        val out = ArrayList<String>()
        var start = 0
        while (start < normalized.length && out.size < MAX_CHUNKS_PER_DOC) {
            val hardEnd = (start + limit).coerceAtMost(normalized.length)
            val end = if (hardEnd == normalized.length) hardEnd else findSplitBoundary(normalized, start, hardEnd)
            val piece = normalized.substring(start, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            if (end >= normalized.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return out
    }

    private fun findSplitBoundary(text: String, start: Int, hardEnd: Int): Int {
        val minKeep = start + ((hardEnd - start) * 0.55).toInt()
        val para = text.lastIndexOf("\n\n", hardEnd)
        if (para >= minKeep) return para + 2
        val nl = text.lastIndexOf('\n', hardEnd)
        if (nl >= minKeep) return nl + 1
        for (i in hardEnd - 1 downTo minKeep) {
            if (text[i] in charArrayOf('。', '！', '？', '.', '!', '?')) return i + 1
        }
        val sp = text.lastIndexOf(' ', hardEnd)
        if (sp >= minKeep) return sp + 1
        return hardEnd
    }

    fun encodeFloatArray(vec: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buf.putFloat(v)
        return buf.array()
    }

    fun decodeFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.size < 4 || bytes.size % 4 != 0) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buf.getFloat()
        return out
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-12) (dot / denom).toFloat() else 0f
    }

    /**
     * Language-agnostic lexical relevance combining Latin word tokens and CJK character bigrams/unigrams.
     */
    fun lexicalOverlapScore(query: String, passage: String): Float {
        val qTokens = extractSearchTerms(query)
        if (qTokens.isEmpty()) return 0f
        val pLower = passage.lowercase(Locale.ROOT)
        var hits = 0f
        var totalWeight = 0f
        for (tok in qTokens) {
            val w = if (tok.length >= 2) 1.5f else 0.7f
            totalWeight += w
            if (pLower.contains(tok)) hits += w
        }
        return if (totalWeight > 0f) (hits / totalWeight).coerceIn(0f, 1f) else 0f
    }

    internal fun extractSearchTerms(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        val terms = LinkedHashSet<String>()
        // Latin / digit words (len >= 2)
        Regex("[a-z0-9_+#.-]{2,}").findAll(lower).forEach { m ->
            val w = m.value.trim('.', '-', '+')
            if (w.length >= 2) terms.add(w)
        }
        // CJK runs: add overlapping bigrams + individual chars (for short keywords)
        val cjkRun = StringBuilder()
        fun flushCjk() {
            val s = cjkRun.toString()
            cjkRun.setLength(0)
            if (s.isEmpty()) return
            for (ch in s) terms.add(ch.toString())
            for (i in 0 until s.length - 1) {
                terms.add(s.substring(i, i + 2))
            }
        }
        for (ch in lower) {
            if (isCjk(ch)) cjkRun.append(ch) else flushCjk()
        }
        flushCjk()
        return terms
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
    }

    fun rankHybridHits(
        query: String,
        queryEmbedding: FloatArray?,
        chunks: List<KnowledgeChunkRecord>,
        topK: Int = 4,
        minScore: Float = 0.08f
    ): List<KnowledgeHit> {
        if (chunks.isEmpty() || topK <= 0) return emptyList()
        val scored = ArrayList<KnowledgeHit>(chunks.size)
        for (chunk in chunks) {
            val lex = lexicalOverlapScore(query, chunk.text)
            val vec = if (queryEmbedding != null && chunk.embedding != null && chunk.embedding.size == queryEmbedding.size) {
                max(0f, cosineSimilarity(queryEmbedding, chunk.embedding))
            } else {
                -1f
            }
            val combined = if (vec >= 0f) {
                0.72f * vec + 0.28f * lex
            } else {
                lex
            }
            if (combined >= minScore) {
                scored.add(
                    KnowledgeHit(
                        chunkId = chunk.id,
                        docId = chunk.docId,
                        docName = chunk.docName,
                        chunkIndex = chunk.chunkIndex,
                        text = chunk.text,
                        score = combined,
                        vectorScore = if (vec >= 0f) vec else 0f,
                        lexicalScore = lex
                    )
                )
            }
        }
        return scored.sortedByDescending { it.score }.take(topK)
    }

    fun buildRagPrompt(hits: List<KnowledgeHit>, userQuestion: String): String {
        if (hits.isEmpty()) return userQuestion
        return buildString {
            append("Use the local knowledge base excerpts below to answer the user's question accurately in the user's language. ")
            append("Cite sources inline like [1], [2]. If the excerpts do not contain the answer, state that clearly.\n\n")
            hits.forEachIndexed { idx, hit ->
                append("[").append(idx + 1).append("] ").append(hit.docName)
                    .append(" (#").append(hit.chunkIndex + 1).append(")\n")
                append(hit.text.trim()).append("\n\n")
            }
            append("Question:\n").append(userQuestion.trim())
        }
    }
}
