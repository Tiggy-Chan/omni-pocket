package com.tiggy.qwenimage.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

fun interface LlmStreamCallback {
    fun onToken(token: String): Boolean
}

data class NativeLlmResult(
    val finish: Int,
    val text: String,
    val error: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val droppedMessages: Int,
    val prefillUs: Long,
    val decodeUs: Long
)

class LocalLlmEngine(private val cacheDirectory: File) : AutoCloseable {
    private val execution = Mutex()
    private val lifecycle = ReentrantReadWriteLock()
    @Volatile private var handle = 0L
    private var loadedKey: String? = null

    suspend fun load(config: File, modelKey: String, threads: Int, contextTokens: Int): String? =
        execution.withLock {
            withContext(Dispatchers.Default) {
                if (handle != 0L && loadedKey == modelKey) return@withContext null
                replaceHandle(0L)
                val created = nativeCreate()
                check(created != 0L) { "无法创建 LLM 会话" }
                // Publish the handle first so cancel() can interrupt the load; only the write lock may free it.
                replaceHandle(created)
                val error = lifecycle.read {
                    nativeLoad(created, config.absolutePath, cacheDirectory.absolutePath, threads, contextTokens)
                }
                if (error != null) replaceHandle(0L) else loadedKey = modelKey
                error
            }
        }

    private fun replaceHandle(next: Long) = lifecycle.write {
        val previous = handle
        handle = next
        loadedKey = null
        if (previous != next) nativeDestroy(previous)
    }

    suspend fun generate(
        conversationId: String,
        messages: List<Pair<String, String>>,
        maxNewTokens: Int,
        thinking: Boolean,
        callback: LlmStreamCallback
    ): NativeLlmResult = execution.withLock {
        withContext(Dispatchers.Default) {
            lifecycle.readLock().lock()
            try {
                val current = handle
                check(current != 0L) { "模型尚未加载" }
                nativeGenerate(current, conversationId, messages.map { it.first }.toTypedArray(),
                    messages.map { it.second }.toTypedArray(), maxNewTokens, thinking, callback)
            } finally {
                lifecycle.readLock().unlock()
            }
        }
    }

    fun cancel() {
        lifecycle.readLock().lock()
        try {
            if (handle != 0L) nativeCancel(handle)
        } finally {
            lifecycle.readLock().unlock()
        }
    }

    suspend fun unload() = execution.withLock {
        withContext(Dispatchers.IO) { close() }
    }

    override fun close() = replaceHandle(0L)

    private companion object {
        init { System.loadLibrary("omni_llm") }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeLoad(
            handle: Long, config: String, cache: String, threads: Int, contextTokens: Int
        ): String?
        @JvmStatic private external fun nativeGenerate(
            handle: Long, conversationId: String, roles: Array<String>, contents: Array<String>,
            maxNewTokens: Int, thinking: Boolean, callback: LlmStreamCallback
        ): NativeLlmResult
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
