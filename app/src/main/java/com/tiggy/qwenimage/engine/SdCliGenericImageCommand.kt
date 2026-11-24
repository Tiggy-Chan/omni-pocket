package com.tiggy.qwenimage.engine

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

internal val SUPPORTED_SD_SAMPLERS = listOf("euler", "euler_a", "dpm++2m", "lcm", "heun")

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
    val useOpenCl: Boolean = false,
    val llmFile: File? = null,
    val vaeFile: File? = null,
    val refImage: File? = null,
    val sampler: String = "euler",
    val vaeTiling: Boolean = true,
    val flashAttn: Boolean = false
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
    require(options.sampler in SUPPORTED_SD_SAMPLERS) { "不支持的采样器：${options.sampler}" }
    require(options.output.extension.equals("png", ignoreCase = true))

    fun number(value: Double) = String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    val isModular = options.llmFile != null && options.vaeFile != null
    return buildList {
        add("/system/bin/nice")
        add("-n")
        add("10")
        add(options.nativeLib.absolutePath)
        add("-M"); add("img_gen")
        if (isModular) {
            add("--diffusion-model"); add(options.modelFile.absolutePath)
            add("--llm"); add(options.llmFile!!.absolutePath)
            add("--vae"); add(options.vaeFile!!.absolutePath)
        } else {
            add("--model"); add(options.modelFile.absolutePath)
        }
        if (options.useOpenCl) {
            add("--backend"); add("diffusion=GPUOpenCL,te=CPU,vae=CPU")
            add("--diffusion-fa")
        } else {
            add("--backend"); add("cpu")
            add("--offload-to-cpu")
            if (options.flashAttn) add("--diffusion-fa")
        }
        if (options.vaeTiling) add("--vae-tiling")
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
        add("--sampling-method"); add(options.sampler)
        options.refImage?.let {
            add("-r"); add(it.absolutePath)
        }
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

/** Terminates a child process cleanly with a bounded wait before falling back to SIGKILL. */
internal fun terminateProcessGracefully(process: Process?, graceMs: Long = 400L) {
    if (process == null) return
    runCatching {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS) && process.isAlive) {
            process.destroyForcibly()
        }
    }
}
