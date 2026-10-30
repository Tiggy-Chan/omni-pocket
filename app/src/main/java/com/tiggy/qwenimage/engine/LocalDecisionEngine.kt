package com.tiggy.qwenimage.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

fun interface DecisionProgressCallback {
    fun onProgress(completed: Int, total: Int): Boolean
}

data class NativeDecisionResult(
    val cancelled: Boolean,
    val resultJson: String,
    val error: String
)

class LocalDecisionEngine(private val cacheDirectory: File) : AutoCloseable {
    private val execution = Mutex()
    private val lifecycle = ReentrantReadWriteLock()
    @Volatile private var handle = 0L
    private var loadedKey: String? = null

    suspend fun load(config: File, modelKey: String, threads: Int): String? = execution.withLock {
        withContext(Dispatchers.Default) {
            if (handle != 0L && loadedKey == modelKey) return@withContext null
            replaceHandle(0L)
            val created = nativeCreate()
            check(created != 0L) { "无法创建决策会话" }
            // Publish the handle first so cancel() can interrupt the load; only the write lock may free it.
            replaceHandle(created)
            val error = lifecycle.read {
                nativeLoad(created, config.absolutePath, cacheDirectory.absolutePath, threads)
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

    suspend fun slotLabels(): List<String> = execution.withLock {
        withContext(Dispatchers.Default) {
            lifecycle.readLock().lock()
            try {
                val current = handle
                check(current != 0L) { "决策模型尚未加载" }
                nativeSlotLabels(current).map { it.toString() }
            } finally {
                lifecycle.readLock().unlock()
            }
        }
    }

    suspend fun decide(
        state: String,
        suffixes: List<String>,
        optionCounts: List<Int>,
        callback: DecisionProgressCallback
    ): NativeDecisionResult = execution.withLock {
        withContext(Dispatchers.Default) {
            lifecycle.readLock().lock()
            try {
                val current = handle
                check(current != 0L) { "决策模型尚未加载" }
                nativeDecide(current, state, suffixes.toTypedArray(), optionCounts.toIntArray(), callback)
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
        init { System.loadLibrary("omni_decision") }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeLoad(handle: Long, config: String, cache: String, threads: Int): String?
        @JvmStatic private external fun nativeSlotLabels(handle: Long): Array<String>
        @JvmStatic private external fun nativeDecide(
            handle: Long, state: String, suffixes: Array<String>, counts: IntArray,
            callback: DecisionProgressCallback
        ): NativeDecisionResult
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
