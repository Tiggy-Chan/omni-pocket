package com.tiggy.qwenimage.engine

import java.io.File

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
    val threads: Int = 6
)

internal fun buildSdCliVideoCommand(options: SdCliVideoOptions): List<String> {
    require(options.prompt.isNotBlank()) { "视频提示词不能为空" }
    require(options.width > 0 && options.height > 0)
    require(options.frames in 5..25) { "端侧视频帧数必须为 5–25" }
    require(options.fps in 8..30) { "帧率必须为 8–30" }
    require(options.steps in 1..6) { "端侧视频步数必须为 1–6" }
    require(options.threads in 2..8)
    require(options.output.extension.equals("webm", ignoreCase = true))

    return buildList {
        add("/system/bin/nice")
        add("-n")
        add("10")
        add(options.nativeLib.absolutePath)
        add("-M"); add("vid_gen")
        add("--diffusion-model"); add(options.diffusionModel.absolutePath)
        add("--t5xxl"); add(options.textEncoder.absolutePath)
        add("--taesd"); add(options.vae.absolutePath)
        add("--backend"); add("diffusion=GPUOpenCL,te=CPU,vae=CPU")
        add("-t"); add(options.threads.toString())
        add("-p"); add(options.prompt)
        add("-n"); add("deformed, distorted, ugly, blurry, bad hands, dark, low quality, oversaturated, watermark, text")
        add("-W"); add(options.width.toString())
        add("-H"); add(options.height.toString())
        add("--video-frames"); add(options.frames.toString())
        add("--fps"); add(options.fps.toString())
        add("--steps"); add(options.steps.toString())
        add("--seed"); add(options.seed.toString())
        add("--cfg-scale"); add("1.0")
        add("--sampling-method"); add("euler")
        add("--flow-shift"); add(options.shift.toString())
        add("--diffusion-fa")
        options.initImage?.let {
            add("-i")
            add(it.absolutePath)
        }
        add("-o"); add(options.output.absolutePath)
    }
}
