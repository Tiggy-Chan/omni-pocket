package com.tiggy.qwenimage.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufComputeBackendTest {
    @Test
    fun parsesSavedPreferenceAndMigratesLegacyGpuFlag() {
        assertEquals(GgufComputeBackend.Auto, GgufComputeBackend.fromPref(null, legacyGpuFlag = false))
        assertEquals(GgufComputeBackend.OpenCl, GgufComputeBackend.fromPref(null, legacyGpuFlag = true))
        assertEquals(GgufComputeBackend.Vulkan, GgufComputeBackend.fromPref("vulkan", legacyGpuFlag = false))
        assertEquals(GgufComputeBackend.Cpu, GgufComputeBackend.fromPref("CPU", legacyGpuFlag = true))
        assertEquals(GgufComputeBackend.HexagonNpu, GgufComputeBackend.fromPref("hexagon", legacyGpuFlag = false))
        assertEquals(GgufComputeBackend.Auto, GgufComputeBackend.fromPref("unknown_value", legacyGpuFlag = false))
    }

    @Test
    fun checksSelectableBackendsFromHardwareCapabilities() {
        val cpuOnly = GgufBackendCapabilities(hasKleidiAi = true, hasHexagonFastRpc = true)
        assertTrue(cpuOnly.isSelectable(GgufComputeBackend.Auto))
        assertTrue(cpuOnly.isSelectable(GgufComputeBackend.Cpu))
        assertFalse(cpuOnly.isSelectable(GgufComputeBackend.Vulkan))
        assertFalse(cpuOnly.isSelectable(GgufComputeBackend.OpenCl))
        assertFalse(cpuOnly.isSelectable(GgufComputeBackend.HexagonNpu))

        val full = GgufBackendCapabilities(
            hasKleidiAi = true,
            hasVulkan = true,
            vulkanName = "Adreno 750",
            hasOpenCl = true,
            openClName = "QUALCOMM Adreno 750",
            hasHexagonCompiled = true,
            hasHexagonRuntime = true,
            hasHexagonFastRpc = true,
            recommendedThreads = 6
        )
        assertTrue(full.isSelectable(GgufComputeBackend.Vulkan))
        assertTrue(full.isSelectable(GgufComputeBackend.OpenCl))
        assertTrue(full.isSelectable(GgufComputeBackend.HexagonNpu))
    }
}
