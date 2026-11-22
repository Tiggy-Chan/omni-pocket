package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBenchmarkCatalogTest {
    @Test
    fun matchesCommunityReposToCanonicalUpstreamFamiliesWithoutConfusion() {
        val qwen35 = ModelBenchmarkCatalog.matchRepoOrName("taobao-mnn/Qwen3.5-4B-MNN")
        assertNotNull(qwen35)
        assertEquals("qwen3.5-4b", qwen35!!.id)

        val qwen3 = ModelBenchmarkCatalog.matchRepoOrName("unsloth/Qwen3-4B-GGUF")
        assertNotNull(qwen3)
        assertEquals("qwen3-4b", qwen3!!.id)

        val zImage = ModelBenchmarkCatalog.matchRepoOrName("unsloth/Z-Image-Turbo-GGUF")
        assertNotNull(zImage)
        assertEquals("z-image-turbo", zImage!!.id)

        val wan13 = ModelBenchmarkCatalog.matchRepoOrName("samuelchristlie/Wan2.1-T2V-1.3B-GGUF")
        assertNotNull(wan13)
        assertEquals("wan2.1-t2v-1.3b", wan13!!.id)

        val ace = ModelBenchmarkCatalog.matchRepoOrName("audio-cpp/ACE-Step-1.5-Turbo-Q8_0.gguf")
        assertNotNull(ace)
        assertEquals("ace-step-1.5", ace!!.id)
    }

    @Test
    fun neverFabricatesScoreForUnknownRepositories() {
        assertNull(ModelBenchmarkCatalog.matchRepoOrName("random-user/my-finetune-7b-gguf"))
    }

    @Test
    fun identifiesTrustedPackagersAndQuantRetentionHints() {
        assertTrue(ModelBenchmarkCatalog.isTrustedPackager("unsloth"))
        assertTrue(ModelBenchmarkCatalog.isTrustedPackager("Bartowski"))
        assertTrue(ModelBenchmarkCatalog.isTrustedPackager("audio-cpp"))
        assertFalse(ModelBenchmarkCatalog.isTrustedPackager("unknown-uploader"))

        val q4 = ModelBenchmarkCatalog.quantQualityHint("Q4_K_M")
        assertNotNull(q4)
        assertEquals(94, q4!!.retentionPercent)

        val q8 = ModelBenchmarkCatalog.quantQualityHint("Q8_0")
        assertNotNull(q8)
        assertEquals(99, q8!!.retentionPercent)

        val q2 = ModelBenchmarkCatalog.quantQualityHint("Q2_K")
        assertNotNull(q2)
        assertTrue(q2!!.retentionPercent < q4.retentionPercent)
    }

    @Test
    fun evaluatesDeviceRamFitAccurately() {
        val phone16Gb = DeviceHardwareSnapshot(
            totalRamBytes = 16L * 1024 * 1024 * 1024,
            availableRamBytes = 10L * 1024 * 1024 * 1024,
            cpuCores = 8,
            hasVulkan = true,
            hasOpenCl = true,
            socLabel = "SM8650"
        )
        val phone8Gb = phone16Gb.copy(totalRamBytes = 8L * 1024 * 1024 * 1024)

        assertEquals(DeviceFitLevel.Smooth, ModelBenchmarkCatalog.evaluateDeviceFit(2_500_000_000L, phone16Gb))
        assertEquals(DeviceFitLevel.Usable, ModelBenchmarkCatalog.evaluateDeviceFit(2_500_000_000L, phone8Gb))
        assertEquals(DeviceFitLevel.LikelyOom, ModelBenchmarkCatalog.evaluateDeviceFit(7_500_000_000L, phone8Gb))
    }

    @Test
    fun coversAllFiveModalitiesWithAttributedSources() {
        BenchmarkModality.entries.forEach { modality ->
            val items = ModelBenchmarkCatalog.byModality(modality)
            assertTrue("Modality $modality should have reference entries", items.isNotEmpty())
            items.forEach { entry ->
                assertTrue(entry.source.isNotBlank())
                assertTrue(entry.primaryScoreLabel.isNotBlank())
            }
        }
    }
}
