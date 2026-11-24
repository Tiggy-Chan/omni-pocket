package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.ExtractedDocument

enum class DocumentQuickAction {
    Summarize,
    KeyPoints,
    Ask
}

enum class DocumentSummaryStage {
    SinglePass,
    MapChunk,
    ReduceFinal
}

data class DocumentSummaryProgress(
    val stage: DocumentSummaryStage,
    val currentChunk: Int,
    val totalChunks: Int
)

/**
 * Splits long documents according to the active model's effective context window and runs
 * single-pass or Map-Reduce summarization with truthful chunk-level progress callbacks.
 */
object DocumentSummaryPipeline {
    const val MIN_CHUNK_CHARS = 1_200
    const val MAX_CHUNK_CHARS = 14_000
    const val DEFAULT_OVERLAP_CHARS = 180
    const val MAX_CHUNKS = 24

    /** Conservative character budget per chunk accounting for CJK density and system/output tokens. */
    fun chunkBudgetChars(contextTokens: Int, maxNewTokens: Int): Int {
        val availablePromptTokens = (contextTokens - maxNewTokens.coerceAtMost(contextTokens / 2) - 512)
            .coerceAtLeast(600)
        // ~1.6 chars per token safely accommodates dense Chinese + English mixed text.
        return (availablePromptTokens * 1.6).toInt().coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
    }

    fun splitIntoChunks(
        text: String,
        maxChunkChars: Int,
        overlapChars: Int = DEFAULT_OVERLAP_CHARS
    ): List<String> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return emptyList()
        val limit = maxChunkChars.coerceAtLeast(200)
        if (normalized.length <= limit) return listOf(normalized)

        val overlap = overlapChars.coerceIn(0, limit / 4)
        val chunks = ArrayList<String>()
        var start = 0
        while (start < normalized.length && chunks.size < MAX_CHUNKS) {
            val hardEnd = (start + limit).coerceAtMost(normalized.length)
            val end = if (hardEnd == normalized.length) {
                hardEnd
            } else {
                findCleanSplit(normalized, start, hardEnd)
            }
            val piece = normalized.substring(start, end).trim()
            if (piece.isNotEmpty()) chunks.add(piece)
            if (end >= normalized.length) break
            val nextStart = (end - overlap).coerceAtLeast(start + 1)
            start = nextStart
        }
        return chunks
    }

    private fun findCleanSplit(text: String, start: Int, hardEnd: Int): Int {
        val minKeep = start + ((hardEnd - start) * 0.55).toInt()
        val paraBreak = text.lastIndexOf("\n\n", hardEnd)
        if (paraBreak >= minKeep) return paraBreak + 2
        val lineBreak = text.lastIndexOf('\n', hardEnd)
        if (lineBreak >= minKeep) return lineBreak + 1
        for (i in hardEnd - 1 downTo minKeep) {
            if (text[i] in charArrayOf('。', '！', '？', '.', '!', '?')) {
                return i + 1
            }
        }
        val spaceBreak = text.lastIndexOf(' ', hardEnd)
        if (spaceBreak >= minKeep) return spaceBreak + 1
        return hardEnd
    }

    fun buildSinglePassPrompt(
        doc: ExtractedDocument,
        action: DocumentQuickAction,
        userQuestion: String
    ): String {
        val instruction = when (action) {
            DocumentQuickAction.Summarize ->
                "Please read the attached document and write a clear, accurate summary in the same language as the document. Include a brief overview followed by key takeaways."
            DocumentQuickAction.KeyPoints ->
                "Please read the attached document and extract the most important points, data, conclusions, and action items as a clean bulleted list in the document's language."
            DocumentQuickAction.Ask ->
                userQuestion.trim().ifBlank { "Please summarize this document." }
        }
        return buildString {
            append(instruction)
            append("\n\n[Document: ").append(doc.fileName).append("]\n")
            append(doc.text)
        }
    }

    fun buildMapChunkPrompt(
        fileName: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkText: String,
        action: DocumentQuickAction,
        userQuestion: String
    ): String {
        val goal = when (action) {
            DocumentQuickAction.Summarize -> "Extract the core arguments, key facts, and conclusions from this section."
            DocumentQuickAction.KeyPoints -> "List the concrete key points, metrics, and decisions in this section."
            DocumentQuickAction.Ask -> "Extract only information relevant to answering: ${userQuestion.trim()}"
        }
        return buildString {
            append("You are reading part ").append(chunkIndex).append(" of ").append(totalChunks)
            append(" from document \"").append(fileName).append("\".\n")
            append(goal).append(" Be concise and use the document's language.\n\n")
            append(chunkText)
        }
    }

    fun buildReducePrompt(
        fileName: String,
        chunkNotes: List<String>,
        action: DocumentQuickAction,
        userQuestion: String
    ): String {
        val goal = when (action) {
            DocumentQuickAction.Summarize ->
                "Synthesize the section notes below from \"$fileName\" into a cohesive, well-structured final summary in the same language."
            DocumentQuickAction.KeyPoints ->
                "Merge and deduplicate the section notes below from \"$fileName\" into a clear, prioritized bullet list of key points in the same language."
            DocumentQuickAction.Ask ->
                "Using the section notes below from \"$fileName\", accurately answer the user's question:\n${userQuestion.trim()}"
        }
        return buildString {
            append(goal).append("\n\n")
            chunkNotes.forEachIndexed { idx, note ->
                append("### Part ").append(idx + 1).append('\n')
                append(note.trim()).append("\n\n")
            }
        }
    }

    /**
     * Executes single-pass or Map-Reduce summarization over [doc] using the caller-provided
     * [runInference] lambda (which wraps either GGUF or MNN engine).
     */
    suspend fun execute(
        doc: ExtractedDocument,
        action: DocumentQuickAction,
        userQuestion: String,
        contextTokens: Int,
        maxNewTokens: Int,
        isCancelled: () -> Boolean,
        onProgress: (DocumentSummaryProgress) -> Unit,
        runInference: suspend (
            prompt: String,
            stepMaxTokens: Int,
            streamToUser: Boolean
        ) -> NativeLlmResult
    ): NativeLlmResult {
        val budget = chunkBudgetChars(contextTokens, maxNewTokens)
        val chunks = splitIntoChunks(doc.text, budget)
        if (chunks.isEmpty()) {
            return NativeLlmResult(3, "", "Document has no readable text", 0, 0, 0, 0, 0)
        }
        if (chunks.size == 1) {
            onProgress(DocumentSummaryProgress(DocumentSummaryStage.SinglePass, 1, 1))
            val prompt = buildSinglePassPrompt(doc, action, userQuestion)
            return runInference(prompt, maxNewTokens, true)
        }

        val notes = ArrayList<String>(chunks.size)
        val mapMaxTokens = (maxNewTokens / 2).coerceIn(220, 512)
        var totalPromptTokens = 0
        var totalGeneratedTokens = 0
        var totalPrefillUs = 0L
        var totalDecodeUs = 0L

        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled()) {
                return NativeLlmResult(1, "", "", totalPromptTokens, totalGeneratedTokens, 0, totalPrefillUs, totalDecodeUs)
            }
            onProgress(DocumentSummaryProgress(DocumentSummaryStage.MapChunk, index + 1, chunks.size))
            val mapPrompt = buildMapChunkPrompt(
                fileName = doc.fileName,
                chunkIndex = index + 1,
                totalChunks = chunks.size,
                chunkText = chunk,
                action = action,
                userQuestion = userQuestion
            )
            val mapResult = runInference(mapPrompt, mapMaxTokens, false)
            totalPromptTokens += mapResult.promptTokens
            totalGeneratedTokens += mapResult.generatedTokens
            totalPrefillUs += mapResult.prefillUs
            totalDecodeUs += mapResult.decodeUs
            if (mapResult.finish == 1 || isCancelled()) {
                return NativeLlmResult(1, "", "", totalPromptTokens, totalGeneratedTokens, 0, totalPrefillUs, totalDecodeUs)
            }
            if (mapResult.finish == 3 && mapResult.text.isBlank()) {
                return mapResult
            }
            val cleaned = com.tiggy.qwenimage.data.splitReasoning(mapResult.text, false).first.ifBlank { mapResult.text }
            if (cleaned.isNotBlank()) notes.add(cleaned)
        }

        if (isCancelled()) {
            return NativeLlmResult(1, "", "", totalPromptTokens, totalGeneratedTokens, 0, totalPrefillUs, totalDecodeUs)
        }
        onProgress(DocumentSummaryProgress(DocumentSummaryStage.ReduceFinal, chunks.size, chunks.size))
        val reducePrompt = buildReducePrompt(doc.fileName, notes, action, userQuestion)
        val finalResult = runInference(reducePrompt, maxNewTokens, true)
        return finalResult.copy(
            promptTokens = totalPromptTokens + finalResult.promptTokens,
            generatedTokens = totalGeneratedTokens + finalResult.generatedTokens,
            prefillUs = totalPrefillUs + finalResult.prefillUs,
            decodeUs = totalDecodeUs + finalResult.decodeUs
        )
    }
}
