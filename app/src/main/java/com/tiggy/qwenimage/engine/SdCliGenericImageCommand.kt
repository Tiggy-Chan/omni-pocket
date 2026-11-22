package com.tiggy.qwenimage.engine

import java.io.File
import java.util.Locale

internal data class SdCliGenericImageOptions(
    val nativeLib: File,
    val modelFile: File,
    val output: File,
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long,
    val cfg: Double,
    val threads: Int = 6,
    val useOpenCl: Boolean = false
)

internal fun buildSdCliGenericImageCommand(options: SdCliGenericImageOptions): List<String> {
    require(options.prompt.isNotBlank()) { "图像提示词不能为空" }
    require(options.width in 256..1024 && options.height in 256..1024 &&
        options.width % 64 == 0 && options.height % 64 == 0) {
        "图像宽高必须为 256–1024 内的 64 倍数"
    }
    require(options.steps in 1..60) { "步数必须为 1–60" }
    require(options.seed >= 0L) { "seed 不能为负数" }
    require(options.cfg.isFinite() && options.cfg in 0.0..20.0) { "CFG 必须在 0 到 20 之间" }
    require(options.threads in 2..8) { "线程数必须为 2–8" }
    require(options.output.extension.equals("png", ignoreCase = true))

    fun number(value: Double) = String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    return buildList {
        add("/system/bin/nice")
        add("-n")
        add("10")
        add(options.nativeLib.absolutePath)
        add("-M"); add("img_gen")
        add("--model"); add(options.modelFile.absolutePath)
        if (options.useOpenCl) {
            add("--backend"); add("diffusion=GPUOpenCL,te=CPU,vae=CPU")
        } else {
            add("--backend"); add("cpu")
            add("--offload-to-cpu")
        }
        add("--mmap")
        add("-t"); add(options.threads.toString())
        add("-p"); add(options.prompt)
        if (options.negativePrompt.isNotBlank()) {
            add("-n"); add(options.negativePrompt)
        }
        add("-W"); add(options.width.toString())
        add("-H"); add(options.height.toString())
        add("--steps"); add(options.steps.toString())
        add("--seed"); add(options.seed.toString())
        add("--cfg-scale"); add(number(options.cfg))
        add("--sampling-method"); add("euler")
        add("-o"); add(options.output.absolutePath)
    }
}

/** Resolves the unified sd-cli binary, falling back to libmoss_cli.so if present. */
internal fun resolveSdCliBinary(nativeLibraryDir: String): File {
    val unified = File(nativeLibraryDir, "libsd_cli.so")
    if (unified.isFile) return unified
    val legacyMoss = File(nativeLibraryDir, "libmoss_cli.so")
    return if (legacyMoss.isFile) legacyMoss else unified
}
