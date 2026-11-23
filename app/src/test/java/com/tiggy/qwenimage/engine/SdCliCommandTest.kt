package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.VideoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdCliCommandTest {
    private fun options(frames: Int = 25) = SdCliVideoOptions(
        nativeLib = File("/app/libsd_cli.so"),
        diffusionModel = File("/models/wan.gguf"),
        textEncoder = File("/models/t5.gguf"),
        vae = File("/models/tae.safetensors"),
        output = File("/outputs/result.part.webm"),
        prompt = "a red apple",
        width = 480,
        height = 832,
        frames = frames,
        fps = 16,
        steps = 4,
        seed = 123456789L,
        shift = 5f
    )

    @Test
    fun passesEveryUserVisibleParameterToTheCli() {
        val command = buildSdCliVideoCommand(options())

        assertEquals("16", command.valueAfter("--fps"))
        assertEquals("123456789", command.valueAfter("--seed"))
        assertEquals("25", command.valueAfter("--video-frames"))
        assertEquals("4", command.valueAfter("--steps"))
        assertEquals("6", command.valueAfter("-t"))
        assertEquals("480", command.valueAfter("-W"))
        assertEquals("832", command.valueAfter("-H"))
        assertTrue(command.contains("--diffusion-fa"))
    }

    @Test
    fun addsFirstFrameOnlyWhenProvided() {
        val withoutImage = buildSdCliVideoCommand(options())
        val withImage = buildSdCliVideoCommand(options().copy(initImage = File("/cache/first.png")))

        assertFalse(withoutImage.contains("-i"))
        assertEquals("/cache/first.png", withImage.valueAfter("-i"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFramesAboveTheOnDeviceLimitInsteadOfClamping() {
        buildSdCliVideoCommand(options(frames = 81))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFourNPlusOneFrameCounts() {
        buildSdCliVideoCommand(options(frames = 12))
    }

    @Test
    fun supportsCpuFallbackCustomCfgAndVaeTiling() {
        val command = buildSdCliVideoCommand(
            options(frames = 33).copy(
                steps = 10,
                cfgScale = 5.0f,
                useOpenCl = false,
                vaeTiling = true,
                negativePrompt = "blurry"
            )
        )
        assertEquals("cpu", command.valueAfter("--backend"))
        assertTrue(command.contains("--offload-to-cpu"))
        assertTrue(command.contains("--vae-tiling"))
        assertFalse(command.contains("--diffusion-fa"))
        assertEquals("33", command.valueAfter("--video-frames"))
        assertEquals("10", command.valueAfter("--steps"))
        assertEquals("5", command.valueAfter("--cfg-scale"))
        assertEquals("blurry", command.valueAfter("-n"))
    }

    @Test
    fun snapsVideoFramesToFourNPlusOneRule() {
        assertEquals(5, VideoSettings.snapValidFrameCount(1))
        assertEquals(9, VideoSettings.snapValidFrameCount(10))
        assertEquals(13, VideoSettings.snapValidFrameCount(12))
        assertEquals(25, VideoSettings.snapValidFrameCount(25))
        assertEquals(41, VideoSettings.snapValidFrameCount(50))
    }

    @Test
    fun snapsMiniMaxH3FramesToSeventeenKPlusFiveRule() {
        assertEquals(5, VideoSettings.snapValidH3FrameCount(1))
        assertEquals(5, VideoSettings.snapValidH3FrameCount(13))
        assertEquals(22, VideoSettings.snapValidH3FrameCount(14))
        assertEquals(22, VideoSettings.snapValidH3FrameCount(22))
        assertEquals(39, VideoSettings.snapValidH3FrameCount(39))
        assertEquals(56, VideoSettings.snapValidH3FrameCount(56))
        assertEquals(56, VideoSettings.snapValidH3FrameCount(90))
    }

    @Test
    fun buildsMiniMaxH3PiFlowCommandWithDiskStreamingAndAudioVae() {
        val command = buildSdCliVideoCommand(
            SdCliVideoOptions(
                nativeLib = File("/app/libsd_cli.so"),
                diffusionModel = File("/models/minimax_h3_fl2va_pruned-Q2_K.gguf"),
                textEncoder = File("/models/qwen3vl-32B-MiniMax-H3-Q2_K.gguf"),
                vae = File("/models/minimax_h3_video_vae_int8_convrot.safetensors"),
                output = File("/outputs/h3.part.webm"),
                prompt = "a tabby cat surfing an ocean wave",
                width = 480,
                height = 288,
                frames = 22,
                fps = 24,
                steps = 2,
                seed = 7L,
                shift = 12f,
                arch = VideoModelArch.MINIMAX_H3,
                audioVae = File("/models/minimax_h3_audio_vae_fp32.safetensors"),
                piH3Adapter = File("/models/pi_h3_2step_adapter.safetensors"),
                diskStreaming = true,
                maxVramGb = 3.5f,
                scheduler = "piflow",
                extraSampleArgs = "min_raw_t=0,max_raw_t=0.93,final_step_size_scale=0.6"
            )
        )

        assertEquals("/models/qwen3vl-32B-MiniMax-H3-Q2_K.gguf", command.valueAfter("--llm"))
        assertEquals("/models/minimax_h3_video_vae_int8_convrot.safetensors", command.valueAfter("--vae"))
        assertEquals("/models/minimax_h3_audio_vae_fp32.safetensors", command.valueAfter("--audio-vae"))
        assertEquals("/models/pi_h3_2step_adapter.safetensors", command.valueAfter("--lora"))
        assertEquals("at_runtime", command.valueAfter("--lora-apply-mode"))
        assertEquals("piflow", command.valueAfter("--scheduler"))
        assertEquals("min_raw_t=0,max_raw_t=0.93,final_step_size_scale=0.6", command.valueAfter("--extra-sample-args"))
        assertEquals("disk", command.valueAfter("--params-backend"))
        assertEquals("3.5", command.valueAfter("--max-vram"))
        assertEquals("24", command.valueAfter("--fps"))
        assertEquals("22", command.valueAfter("--video-frames"))
        assertEquals("12", command.valueAfter("--flow-shift"))
        assertFalse(command.contains("--t5xxl"))
        assertFalse(command.contains("--taesd"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMiniMaxH3FrameCount() {
        buildSdCliVideoCommand(
            options(frames = 25).copy(arch = VideoModelArch.MINIMAX_H3, fps = 24, shift = 12f)
        )
    }

    private fun List<String>.valueAfter(flag: String): String {
        val index = lastIndexOf(flag)
        assertTrue("missing $flag", index >= 0 && index + 1 < size)
        return this[index + 1]
    }
}
