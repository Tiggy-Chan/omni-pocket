package com.tiggy.qwenimage.data

import org.junit.Test

class ImageCapabilitiesTest {
    private fun manifest(features: Set<String> = emptySet(), tiny: Boolean = true, real: Boolean = false): ModelManifest {
        val paths = buildList {
            if (tiny) addAll(listOf("vae_decoder_tiny.mnn", "vae_encoder_tiny.mnn"))
            if (real) addAll(listOf("vae_decoder.mnn", "vae_encoder.mnn"))
        }
        return ModelManifest(
            modelId = "qwen-image-2.1-uc-q4km",
            sourceRepo = "owner/repo",
            revision = "main",
            artifacts = paths.map { ModelArtifact(it, 1, "0".repeat(64)) },
            features = features + "text-to-image"
        )
    }

    @Test
    fun acceptsAnInstalledRealVae() {
        GenerationSettings(prompt = "a red apple", useTinyVae = false).validateFor(manifest(tiny = false, real = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnUnavailableSelectedVae() {
        GenerationSettings(prompt = "a red apple", useTinyVae = false).validateFor(manifest(tiny = true, real = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTurboFromAnotherModel() {
        GenerationSettings(prompt = "a red apple", officialTurbo = true).validateFor(manifest())
    }

    @Test
    fun acceptsTurboDeclaredByTheCurrentModel() {
        GenerationSettings(prompt = "a red apple", officialTurbo = true, steps = 8).validateFor(manifest(features = setOf("official-turbo")))
    }
}
