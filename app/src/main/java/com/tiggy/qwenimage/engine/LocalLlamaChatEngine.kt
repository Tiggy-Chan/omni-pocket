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
 * General-purpose GGUF chat engine powered by the bundled llama.cpp runtime (libomni_decision.so).
 * Supports any llama.cpp-compatible GGUF model downloaded from Hugging Face / hf-mirror.
 */
class LocalLlamaChatEngine : AutoCloseable {
    private val execution = Mutex()
    private val lifecycle = ReentrantReadWriteLock()
    @Volatile private var handle = 0L
    private var loadedKey: String? = null
    @Volatile var effectiveContextTokens: Int = 0
        private set
    @Volatile var modelTrainContextTokens: Int = 0
        private set

    suspend fun load(
        modelFile: File,
        modelKey: String,
        threads: Int,
        contextTokens: Int,
        kvPrecision: KvCachePrecision = KvCachePrecision.Fp16,
        gpuLayers: Int = 0
    ): String? =
        execution.withLock {
            withContext(Dispatchers.Default) {
                val effectiveKey = "$modelKey:$threads:$contextTokens:${kvPrecision.nativeMode}:$gpuLayers"
                if (handle != 0L && loadedKey == effectiveKey) return@withContext null
                replaceHandle(0L)
                val created = nativeCreate()
                check(created != 0L) { "无法创建 GGUF 对话会话" }
                replaceHandle(created)
                val error = lifecycle.read {
                    nativeLoad(created, modelFile.absolutePath, threads, contextTokens, kvPrecision.nativeMode, gpuLayers)
                }
                if (error != null) {
                    replaceHandle(0L)
                } else {
                    loadedKey = effectiveKey
                    effectiveContextTokens = lifecycle.read { if (handle != 0L) nativeEffectiveContext(handle) else 0 }
                    modelTrainContextTokens = lifecycle.read { if (handle != 0L) nativeTrainContext(handle) else 0 }
                }
                error
            }
        }

    private fun replaceHandle(next: Long) = lifecycle.write {
        val previous = handle
        handle = next
        loadedKey = null
        if (next == 0L) {
            effectiveContextTokens = 0
            modelTrainContextTokens = 0
        }
        if (previous != next) nativeDestroy(previous)
    }

    suspend fun generate(
        conversationId: String,
        messages: List<Pair<String, String>>,
        maxNewTokens: Int,
        thinking: Boolean,
        sampling: ChatSamplingParams = ChatSamplingParams(),
        callback: LlmStreamCallback
    ): NativeLlmResult = execution.withLock {
        withContext(Dispatchers.Default) {
            lifecycle.readLock().lock()
            try {
                val current = handle
                check(current != 0L) { "GGUF 模型尚未加载" }
                nativeGenerate(
                    current,
                    conversationId,
                    messages.map { it.first }.toTypedArray(),
                    messages.map { it.second }.toTypedArray(),
                    maxNewTokens,
                    thinking,
                    sampling.temperature,
                    sampling.topP,
                    sampling.topK,
                    sampling.repeatPenalty,
                    callback
                )
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

    companion object {
        init { System.loadLibrary("omni_decision") }

        fun configureCacheDir(cacheDir: File) {
            runCatching {
                cacheDir.mkdirs()
                nativeSetCacheDir(cacheDir.absolutePath)
            }
        }

        fun hasGpuBackend(): Boolean = runCatching { nativeHasGpuBackend() }.getOrDefault(false)

        fun gpuDeviceName(): String = runCatching { nativeGpuDeviceName().orEmpty() }.getOrDefault("")

        @JvmStatic private external fun nativeSetCacheDir(cacheDir: String)
        @JvmStatic private external fun nativeHasGpuBackend(): Boolean
        @JvmStatic private external fun nativeGpuDeviceName(): String?
        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeLoad(
            handle: Long, modelPath: String, threads: Int, contextTokens: Int, kvQuantMode: Int, gpuLayers: Int
        ): String?
        @JvmStatic private external fun nativeEffectiveContext(handle: Long): Int
        @JvmStatic private external fun nativeTrainContext(handle: Long): Int
        @JvmStatic private external fun nativeGenerate(
            handle: Long, conversationId: String, roles: Array<String>, contents: Array<String>,
            maxNewTokens: Int, thinking: Boolean, temperature: Float, topP: Float, topK: Int, repeatPenalty: Float,
            callback: LlmStreamCallback
        ): NativeLlmResult
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
