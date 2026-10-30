package com.tiggy.qwenimage.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide guard so only one heavy local engine runs at a time. Owners are compared by value:
 * callers rebuild owner strings such as "qwen:$id" for acquire and release.
 */
class LocalInferenceCoordinator {
    private val lock = Any()
    private val listeners = mutableMapOf<String, suspend () -> Unit>()
    private val _activeOwner = MutableStateFlow<String?>(null)
    val activeOwner: StateFlow<String?> = _activeOwner.asStateFlow()

    fun acquire(owner: String): Boolean = synchronized(lock) {
        if (_activeOwner.value != null) return false
        _activeOwner.value = owner
        true
    }

    fun release(owner: String) {
        synchronized(lock) {
            if (_activeOwner.value == owner) _activeOwner.value = null
        }
    }

    fun registerEvictor(owner: String, evict: suspend () -> Unit) {
        synchronized(listeners) { listeners[owner] = evict }
    }

    fun unregisterEvictor(owner: String) {
        synchronized(listeners) { listeners.remove(owner) }
    }

    suspend fun evictOthers(owner: String) {
        val modality = owner.substringBefore(':')
        val callbacks = synchronized(listeners) {
            listeners.filterKeys { it.substringBefore(':') != modality }.values.toList()
        }
        callbacks.forEach { it() }
    }

    suspend fun <T> withInference(owner: String, block: suspend () -> T): T? {
        if (!acquire(owner)) return null
        return try {
            evictOthers(owner)
            block()
        } finally {
            release(owner)
        }
    }
}
