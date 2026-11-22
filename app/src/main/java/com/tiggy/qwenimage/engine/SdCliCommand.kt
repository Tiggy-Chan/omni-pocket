package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.VideoSettings
import java.io.File
import java.util.Locale

internal data class SdCliVideoOptions(
    val nativeLib: File,
    val diffusionModel: File,
    val textEncoder: File,
    val vae: File,
    val output: File,
    val prompt: String,
    val width: Int,
    val height: Int,
    val frames: Int,
    val fps: Int,
    val steps: Int,
    val seed: Long,
    val shift: Float,
    val initImage: File? = null,
    val threads: Int = 6,
    val negativePrompt: String = VideoSettings.DEFAULT_VIDEO_NEGATIVE_PROMPT,
    val cfgScale: Float = 1.0f,
    val useOpenCl: Boolean = true,
    val vaeTiling: Boolean = false
)

internal fun buildSdCliVideoCommand(options: SdCliVideoOptions): List<String> {
    require(options.prompt.isNotBlank()) { "视频提示词不能为空" }
    require(options.width > 0 && options.height > 0 && options.width % 32 == 0 && options.height % 32 == 0) {
        "视频尺寸必须为 32 的倍数"
    }
    require(options.frames in 5..41 && (options.frames - 1) % 4 == 0) { "端侧视频帧数必须为 5–41 且满足 4n+1" }
    require(options.fps in 8..30) { "帧率必须为 8–30" }
    require(options.steps in 1..12) { "端侧视频步数必须为 1–12" }
    require(options.cfgScale.isFinite() && options.cfgScale in 0.5f..15.0f) { "CFG 必须在 0.5 到 15 之间" }
    require(options.threads in 2..8)
    require(options.output.extension.equals("webm", ignoreCase = true))

    fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    return buildList {
        add("/system/bin/nice")
        add("-n")
        add("10")
        add(options.nativeLib.absolutePath)
        add("-M"); add("vid_gen")
        add("--diffusion-model"); add(options.diffusionModel.absolutePath)
        add("--t5xxl"); add(options.textEncoder.absolutePath)
        add("--taesd"); add(options.vae.absolutePath)
        // VAE must stay on CPU for Wan2.x because vae=GPUOpenCL produces purple noise corruption on mobile Adreno.
        if (options.useOpenCl) {
            add("--backend"); add("diffusion=GPUOpenCL,te=CPU,vae=CPU")
            add("--diffusion-fa")
        } else {
            add("--backend"); add("cpu")
            add("--offload-to-cpu")
        }
        if (options.vaeTiling) add("--vae-tiling")
        add("-t"); add(options.threads.toString())
        add("-p"); add(options.prompt)
        if (options.negativePrompt.isNotBlank()) {
            add("-n"); add(options.negativePrompt)
        }
        add("-W"); add(options.width.toString())
        add("-H"); add(options.height.toString())
        add("--video-frames"); add(options.frames.toString())
        add("--fps"); add(options.fps.toString())
        add("--steps"); add(options.steps.toString())
        add("--seed"); add(options.seed.toString())
        add("--cfg-scale"); add(fmt(options.cfgScale))
        add("--sampling-method"); add("euler")
        add("--flow-shift"); add(fmt(options.shift))
        options.initImage?.let {
            add("-i")
            add(it.absolutePath)
        }
        add("-o"); add(options.output.absolutePath)
    }
}
