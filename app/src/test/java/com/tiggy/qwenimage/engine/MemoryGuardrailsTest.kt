package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGuardrailsTest {

    @Test
    fun `blocks video generation on 6GB device before OOM`() {
        val gb = 1_000_000_000L
        val assessment = MemoryGuardrails.evaluateMemoryRequirement(
            totalRamBytes = 6 * gb,
            availRamBytes = 2_500_000_000L,
            lowMemory = false,
            requiredTotalRamBytes = 10 * gb,
            requiredAvailRamBytes = 4 * gb,
            taskLabel = "Wan2.2 视频"
        )
        assertTrue(assessment.blocked)
        assertTrue(assessment.message!!.contains("至少需要"))
    }

    @Test
    fun `allows generation on 16GB device with healthy available memory`() {
        val gb = 1_000_000_000L
        val assessment = MemoryGuardrails.evaluateMemoryRequirement(
            totalRamBytes = 16 * gb,
            availRamBytes = 8 * gb,
            lowMemory = false,
            requiredTotalRamBytes = 10 * gb,
            requiredAvailRamBytes = 4 * gb,
            taskLabel = "Wan2.2 视频"
        )
        assertFalse(assessment.blocked)
        assertFalse(assessment.warning)
    }

    @Test
    fun `diagnoses exit code 137 and OpenCL errors into readable Chinese`() {
        val oom = MemoryGuardrails.diagnoseNativeFailure(137, "std::bad_alloc")
        assertTrue(oom.contains("内存或显存不足"))
        val cl = MemoryGuardrails.diagnoseNativeFailure(1, "ggml_opencl: build program error -11")
        assertTrue(cl.contains("OpenCL"))
    }

    @Test
    fun `thermal profiles map to deterministic thread counts`() {
        assertEquals(3, ThermalPowerProfile.Eco.threadsFor(6))
        assertEquals(6, ThermalPowerProfile.Balanced.threadsFor(6))
        assertEquals(8, ThermalPowerProfile.Performance.threadsFor(6))
    }
}
