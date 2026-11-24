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

data class ChatSamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f
)

enum class KvCachePrecision(val nativeMode: Int, val label: String, val detail: String) {
    Fp16(0, "FP16", "标准精度"),
    Q8_0(1, "Q8_0", "约省一半 KV 内存"),
    Q4_0(2, "Q4_0", "长上下文极致省内存")
}

enum class GgufComputeBackend(val nativeCode: Int, val prefKey: String) {
    Auto(0, "auto"),
    Cpu(1, "cpu"),
    Vulkan(2, "vulkan"),
    OpenCl(3, "opencl"),
    HexagonNpu(4, "hexagon");

    companion object {
        fun fromPref(raw: String?, legacyGpuFlag: Boolean = false): GgufComputeBackend {
            if (!raw.isNullOrBlank()) {
                entries.firstOrNull { it.prefKey.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                    ?.let { return it }
            }
            return if (legacyGpuFlag) OpenCl else Auto
        }
    }
}

data class GgufBackendCapabilities(
    val hasKleidiAi: Boolean = false,
    val hasVulkan: Boolean = false,
    val vulkanName: String = "",
    val hasOpenCl: Boolean = false,
    val openClName: String = "",
    val hasHexagonCompiled: Boolean = false,
    val hasHexagonRuntime: Boolean = false,
    val hasHexagonFastRpc: Boolean = false,
    val recommendedThreads: Int = 4
) {
    fun isSelectable(backend: GgufComputeBackend): Boolean = when (backend) {
        GgufComputeBackend.Auto, GgufComputeBackend.Cpu -> true
        GgufComputeBackend.Vulkan -> hasVulkan
        GgufComputeBackend.OpenCl -> hasOpenCl
        GgufComputeBackend.HexagonNpu -> hasHexagonRuntime
    }
}

class LocalLlmEngine(private val cacheDirectory: File) : AutoCloseable {
    private val execution = Mutex()
    private val lifecycle = ReentrantReadWriteLock()
    @Volatile private var handle = 0L
    private var loadedKey: String? = null

    suspend fun load(
        config: File,
        modelKey: String,
        threads: Int,
        contextTokens: Int,
        useOpenCl: Boolean = false
    ): String? =
        execution.withLock {
            withContext(Dispatchers.Default) {
                val effectiveKey = "$modelKey:$threads:$contextTokens:$useOpenCl"
                if (handle != 0L && loadedKey == effectiveKey) return@withContext null
                replaceHandle(0L)
                val created = nativeCreate()
                check(created != 0L) { "无法创建 LLM 会话" }
                // Publish the handle first so cancel() can interrupt the load; only the write lock may free it.
                replaceHandle(created)
                val error = lifecycle.read {
                    nativeLoad(created, config.absolutePath, cacheDirectory.absolutePath, threads, contextTokens, useOpenCl)
                }
                if (error != null) replaceHandle(0L) else loadedKey = effectiveKey
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
        sampling: ChatSamplingParams = ChatSamplingParams(),
        callback: LlmStreamCallback
    ): NativeLlmResult = execution.withLock {
        withContext(Dispatchers.Default) {
            lifecycle.readLock().lock()
            try {
                val current = handle
                check(current != 0L) { "模型尚未加载" }
                nativeGenerate(
                    current,
                    conversationId,
                    messages.map { it.first }.toTypedArray(),
                    messages.map { it.second }.toTypedArray(),
                    maxNewTokens,
                    thinking,
                    sampling.temperature,
                    sampling.topP,
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

    private companion object {
        init { System.loadLibrary("omni_llm") }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeLoad(
            handle: Long, config: String, cache: String, threads: Int, contextTokens: Int, useOpenCl: Boolean
        ): String?
        @JvmStatic private external fun nativeGenerate(
            handle: Long, conversationId: String, roles: Array<String>, contents: Array<String>,
            maxNewTokens: Int, thinking: Boolean, temperature: Float, topP: Float, repeatPenalty: Float,
            callback: LlmStreamCallback
        ): NativeLlmResult
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
