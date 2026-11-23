package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.VideoSettings
import java.io.File
import java.util.Locale

internal enum class VideoModelArch {
    WAN,
    MINIMAX_H3
}

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
    val vaeTiling: Boolean = false,
    val arch: VideoModelArch = VideoModelArch.WAN,
    val audioVae: File? = null,
    val piH3Adapter: File? = null,
    val endImage: File? = null,
    val diskStreaming: Boolean = false,
    val maxVramGb: Float? = null,
    val scheduler: String? = null,
    val extraSampleArgs: String? = null
)

internal fun buildSdCliVideoCommand(options: SdCliVideoOptions): List<String> {
    require(options.prompt.isNotBlank()) { "视频提示词不能为空" }
    require(options.width > 0 && options.height > 0 && options.width % 32 == 0 && options.height % 32 == 0) {
        "视频尺寸必须为 32 的倍数"
    }
    if (options.arch == VideoModelArch.MINIMAX_H3) {
        require(VideoSettings.isValidH3FrameCount(options.frames, max = 56)) {
            "端侧 MiniMax-H3 帧数必须为 5–56 且满足 17k+5"
        }
    } else {
        require(options.frames in 5..41 && (options.frames - 1) % 4 == 0) { "端侧视频帧数必须为 5–41 且满足 4n+1" }
    }
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
        when (options.arch) {
            VideoModelArch.WAN -> {
                add("--t5xxl"); add(options.textEncoder.absolutePath)
                add("--taesd"); add(options.vae.absolutePath)
            }
            VideoModelArch.MINIMAX_H3 -> {
                add("--llm"); add(options.textEncoder.absolutePath)
                add("--vae"); add(options.vae.absolutePath)
                options.audioVae?.let {
                    add("--audio-vae"); add(it.absolutePath)
                }
                options.piH3Adapter?.let {
                    add("--lora"); add(it.absolutePath)
                    add("--lora-apply-mode"); add("at_runtime")
                }
            }
        }
        // VAE must stay on CPU because vae=GPUOpenCL produces purple noise corruption on mobile Adreno.
        if (options.useOpenCl) {
            add("--backend"); add("diffusion=GPUOpenCL,te=CPU,vae=CPU")
            add("--diffusion-fa")
        } else {
            add("--backend"); add("cpu")
            if (!options.diskStreaming) {
                add("--offload-to-cpu")
            }
        }
        if (options.diskStreaming) {
            add("--params-backend"); add("disk")
            val budget = options.maxVramGb
            if (budget != null && budget.isFinite() && budget > 0f) {
                add("--max-vram"); add(fmt(budget))
            }
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
        add("--fps"); add((if (options.arch == VideoModelArch.MINIMAX_H3) 24 else options.fps).toString())
        add("--steps"); add(options.steps.toString())
        add("--seed"); add(options.seed.toString())
        add("--cfg-scale"); add(fmt(options.cfgScale))
        add("--sampling-method"); add("euler")
        options.scheduler?.takeIf { it.isNotBlank() }?.let {
            add("--scheduler"); add(it)
        }
        options.extraSampleArgs?.takeIf { it.isNotBlank() }?.let {
            add("--extra-sample-args"); add(it)
        }
        add("--flow-shift"); add(fmt(options.shift))
        options.initImage?.let {
            add("-i")
            add(it.absolutePath)
        }
        options.endImage?.let {
            add("--end-img")
            add(it.absolutePath)
        }
        add("-o"); add(options.output.absolutePath)
    }
}
