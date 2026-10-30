package com.tiggy.qwenimage.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInferenceCoordinatorTest {
    @Test
    fun ownerMustReleaseBeforeAnotherTaskCanRun() = runBlocking {
        val coordinator = LocalInferenceCoordinator()
        assertTrue(coordinator.acquire("qwen:one"))
        assertFalse(coordinator.acquire("llm:two"))
        assertNull(coordinator.withInference("llm:two") { error("must not execute") })
        assertEquals("qwen:one", coordinator.activeOwner.value)

        coordinator.release("qwen:one")

        assertNull(coordinator.activeOwner.value)
        assertEquals("answer", coordinator.withInference("llm:two") { "answer" })
        assertNull(coordinator.activeOwner.value)
    }

    @Test
    fun releaseMatchesOwnerByValueNotIdentity() {
        val coordinator = LocalInferenceCoordinator()
        val id = "job-" + System.nanoTime()
        assertTrue(coordinator.acquire(StringBuilder("qwen:").append(id).toString()))
        assertFalse(coordinator.acquire("llm:other"))

        coordinator.release(StringBuilder("qwen:").append(id).toString())

        assertNull(coordinator.activeOwner.value)
        assertTrue(coordinator.acquire("decision:request"))
        coordinator.release("qwen:$id")
        assertEquals("decision:request", coordinator.activeOwner.value)
    }

    @Test
    fun evictionHappensBeforeInferenceAndAlwaysReleases() = runBlocking {
        val coordinator = LocalInferenceCoordinator()
        val events = mutableListOf<String>()
        coordinator.registerEvictor("qwen:resident") { events += "evicted" }
        coordinator.registerEvictor("llm:resident") { events += "llm" }
        runCatching {
            coordinator.withInference("llm:one") {
                events += "running"
                error("cancelled")
            }
        }
        assertEquals(listOf("evicted", "running"), events)
        assertTrue(coordinator.acquire("qwen:two"))
        coordinator.release("qwen:two")
    }
}
