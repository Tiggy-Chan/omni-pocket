package com.tiggy.qwenimage.engine

import java.io.File
import java.util.Locale

internal data class SdCliMossOptions(
    val nativeLib: File,
    val diffusionModel: File,
    val textEncoder: File,
    val vae: File,
    val tokenizer: File,
    val output: File,
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long,
    val cfg: Double,
    val shift: Double,
    val threads: Int = 6
)

internal fun buildSdCliMossCommand(options: SdCliMossOptions): List<String> {
    require(options.prompt.isNotBlank()) { "MOSS 提示词不能为空" }
    require(options.width > 0 && options.height > 0 && options.width % 16 == 0 && options.height % 16 == 0) {
        "MOSS 宽高必须为 16 的倍数"
    }
    require(options.steps in 1..100) { "本机 MOSS 步数必须为 1–100" }
    require(options.seed >= 0) { "seed 不能为负数" }
    require(options.cfg.isFinite() && options.cfg in 0.0..20.0) { "CFG 必须位于 0 到 20" }
    require(options.shift.isFinite() && options.shift in 0.0..100.0) { "shift 必须位于 0 到 100" }
    require(options.threads in 2..8)
    require(options.output.extension.equals("png", ignoreCase = true))

    fun number(value: Double) = String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
    return buildList {
        add("/system/bin/nice")
        add("-n")
        add("10")
        add(options.nativeLib.absolutePath)
        add("-M"); add("img_gen")
        add("--diffusion-model"); add(options.diffusionModel.absolutePath)
        add("--llm"); add(options.textEncoder.absolutePath)
        add("--vae"); add(options.vae.absolutePath)
        add("--vae-format"); add("flux2")
        add("--tokenizer"); add(options.tokenizer.absolutePath)
        add("--backend"); add("cpu")
        add("--offload-to-cpu")
        add("--mmap")
        add("-t"); add(options.threads.toString())
        add("-p"); add(options.prompt)
        add("-n"); add(options.negativePrompt)
        add("-W"); add(options.width.toString())
        add("-H"); add(options.height.toString())
        add("--steps"); add(options.steps.toString())
        add("--seed"); add(options.seed.toString())
        add("--cfg-scale"); add(number(options.cfg))
        add("--sampling-method"); add("euler")
        add("--scheduler"); add("discrete")
        add("--flow-shift"); add(number(options.shift))
        add("-o"); add(options.output.absolutePath)
    }
}
