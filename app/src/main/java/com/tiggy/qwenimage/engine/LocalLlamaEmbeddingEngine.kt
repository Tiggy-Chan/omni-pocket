package com.tiggy.qwenimage.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * On-device GGUF text embedding engine backed by llama.cpp inside libomni_decision.so.
 * Supports both encoder-only models (BGE / BERT / Nomic-BERT) and causal embedding models (Qwen3-Embedding).
 */
class LocalLlamaEmbeddingEngine : AutoCloseable {
    private val execution = Mutex()
    private val lifecycle = ReentrantReadWriteLock()
    @Volatile private var handle = 0L
    private var loadedKey: String? = null
    @Volatile var dimension: Int = 0
        private set

    suspend fun load(
        modelFile: File,
        modelKey: String,
        threads: Int = 4,
        contextTokens: Int = 1024
    ): String? = execution.withLock {
        withContext(Dispatchers.Default) {
            val effectiveKey = "$modelKey:$threads:$contextTokens"
            if (handle != 0L && loadedKey == effectiveKey) return@withContext null
            replaceHandle(0L)
            val created = nativeCreate()
            check(created != 0L) { "Failed to allocate native embedding session" }
            replaceHandle(created)
            val err = lifecycle.read {
                nativeLoad(created, modelFile.absolutePath, threads, contextTokens)
            }
            if (err != null) {
                replaceHandle(0L)
            } else {
                loadedKey = effectiveKey
                dimension = lifecycle.read { if (handle != 0L) nativeDimension(handle) else 0 }
            }
            err
        }
    }

    suspend fun embed(text: String): FloatArray? = execution.withLock {
        withContext(Dispatchers.Default) {
            lifecycle.read {
                val current = handle
                if (current == 0L) return@read null
                nativeEmbed(current, text)
            }
        }
    }

    fun cancel() {
        lifecycle.read {
            if (handle != 0L) nativeCancel(handle)
        }
    }

    suspend fun unload() = execution.withLock {
        withContext(Dispatchers.IO) { close() }
    }

    override fun close() = replaceHandle(0L)

    private fun replaceHandle(next: Long) = lifecycle.write {
        val previous = handle
        handle = next
        loadedKey = null
        if (next == 0L) dimension = 0
        if (previous != next) nativeDestroy(previous)
    }

    companion object {
        init { System.loadLibrary("omni_decision") }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeLoad(handle: Long, modelPath: String, threads: Int, contextTokens: Int): String?
        @JvmStatic private external fun nativeDimension(handle: Long): Int
        @JvmStatic private external fun nativeEmbed(handle: Long, text: String): FloatArray?
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
