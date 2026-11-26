package com.tiggy.qwenimage.engine

import android.app.ActivityManager
import android.content.Context
import java.io.IOException
import java.util.Locale

enum class ThermalPowerProfile {
    Eco,
    Balanced,
    Performance;

    fun threadsFor(recommendedPerfThreads: Int): Int {
        val perf = recommendedPerfThreads.coerceIn(4, 6)
        return when (this) {
            Eco -> 3
            Balanced -> perf
            Performance -> (perf + 2).coerceAtMost(8)
        }
    }

    companion object {
        fun fromPref(raw: String?): ThermalPowerProfile =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Balanced
    }
}

data class MemoryAssessment(
    val blocked: Boolean,
    val warning: Boolean,
    val message: String? = null
)

/**
 * Pre-flight memory guardrails and human-readable failure diagnostics for heavy on-device
 * diffusion / video / audio / LLM workloads. Prevents silent LMK / OOM kills on 6-8 GB phones
 * by surfacing clear, actionable Chinese diagnostics before or after native execution.
 */
object MemoryGuardrails {
    private const val GB = 1_000_000_000L

    fun evaluateMemoryRequirement(
        totalRamBytes: Long,
        availRamBytes: Long,
        lowMemory: Boolean,
        requiredTotalRamBytes: Long,
        requiredAvailRamBytes: Long,
        taskLabel: String
    ): MemoryAssessment {
        val totalGb = totalRamBytes / GB.toDouble()
        val availGb = availRamBytes / GB.toDouble()
        val needTotalGb = requiredTotalRamBytes / GB.toDouble()
        val needAvailGb = requiredAvailRamBytes / GB.toDouble()

        if (totalRamBytes > 0L && totalRamBytes < (requiredTotalRamBytes * 0.80).toLong()) {
            return MemoryAssessment(
                blocked = true,
                warning = true,
                message = String.format(
                    Locale.US,
                    "当前设备总内存约 %.1f GB，运行「%s」至少需要约 %.0f GB 内存，强行运行会触发系统内存回收闪退。建议使用更轻量模型或降低分辨率。",
                    totalGb, taskLabel, needTotalGb
                )
            )
        }
        if (lowMemory || (availRamBytes > 0L && availRamBytes < (requiredAvailRamBytes * 0.65).toLong())) {
            return MemoryAssessment(
                blocked = true,
                warning = true,
                message = String.format(
                    Locale.US,
                    "当前可用内存不足（剩余 %.1f GB / 总计 %.1f GB），运行「%s」建议至少保留 %.1f GB 可用内存。请先清理后台应用后再试。",
                    availGb, totalGb, taskLabel, needAvailGb
                )
            )
        }
        if (availRamBytes > 0L && availRamBytes < requiredAvailRamBytes) {
            return MemoryAssessment(
                blocked = false,
                warning = true,
                message = String.format(
                    Locale.US,
                    "当前可用内存偏紧（%.1f GB），运行「%s」期间建议保持前台并关闭其他占用内存的应用",
                    availGb, taskLabel
                )
            )
        }
        return MemoryAssessment(blocked = false, warning = false, message = null)
    }

    fun checkBeforeRun(
        context: Context,
        requiredTotalRamBytes: Long,
        requiredAvailRamBytes: Long,
        taskLabel: String
    ): MemoryAssessment {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MemoryAssessment(false, false)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val assessment = evaluateMemoryRequirement(
            totalRamBytes = info.totalMem,
            availRamBytes = info.availMem,
            lowMemory = info.lowMemory,
            requiredTotalRamBytes = requiredTotalRamBytes,
            requiredAvailRamBytes = requiredAvailRamBytes,
            taskLabel = taskLabel
        )
        if (assessment.blocked) {
            throw IOException(assessment.message ?: "设备内存不足，无法启动 $taskLabel")
        }
        return assessment
    }

    fun diagnoseNativeFailure(
        exitCode: Int,
        outputTail: String,
        context: Context? = null
    ): String {
        val lower = outputTail.lowercase(Locale.ROOT)
        val memSuffix = context?.let { ctx ->
            runCatching {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching ""
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                String.format(Locale.US, "（当前可用内存 %.1f / %.1f GB）", mi.availMem / GB.toDouble(), mi.totalMem / GB.toDouble())
            }.getOrDefault("")
        }.orEmpty()

        return when {
            exitCode == 137 || exitCode == -9 || lower.contains("bad_alloc") ||
                lower.contains("out of memory") || lower.contains("failed to allocate") ||
                lower.contains("cl_out_of_resources") || lower.contains("cl_mem_object_allocation_failure") ->
                "内存或显存不足，推理进程被中止$memSuffix。建议清理后台应用、开启 VAE 分块、降低分辨率或换用更低量化模型。"
            lower.contains("opencl") && (lower.contains("error") || lower.contains("fault") || lower.contains("build program")) ->
                "OpenCL GPU 驱动执行异常，建议在参数中关闭 OpenCL GPU 改为 CPU 稳定模式重试。"
            lower.contains("unknown model architecture") || lower.contains("unsupported") || lower.contains("invalid gguf") ->
                "模型架构或文件格式暂不兼容，请核对文件完整性或在模型页选择兼容架构的权重。"
            lower.contains("no such file") || lower.contains("failed to open") ->
                "模型或配套组件文件缺失，请在「模型 → 本地模型」检查是否缺少必需组件并重新下载。"
            else -> {
                val snippet = outputTail.trim().takeLast(360)
                if (snippet.isEmpty()) "推理失败（退出码 $exitCode）$memSuffix"
                else "推理失败（退出码 $exitCode）$memSuffix\n$snippet"
            }
        }
    }
}
