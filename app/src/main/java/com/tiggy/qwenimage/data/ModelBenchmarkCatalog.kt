package com.tiggy.qwenimage.data

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.Locale

data class DeviceHardwareSnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCores: Int,
    val hasVulkan: Boolean,
    val hasOpenCl: Boolean,
    val socLabel: String
) {
    val totalRamGb: Double get() = totalRamBytes / 1_073_741_824.0
    val availableRamGb: Double get() = availableRamBytes / 1_073_741_824.0

    fun summaryLine(): String {
        val ram = String.format(Locale.getDefault(), "%.0f GB 内存", totalRamGb)
        val accel = buildList {
            if (hasVulkan) add("Vulkan")
            if (hasOpenCl) add("OpenCL")
        }.ifEmpty { listOf("CPU") }.joinToString(" / ")
        return "$ram · ${cpuCores} 核 · $accel"
    }
}

object DeviceHardwareProbe {
    fun read(context: Context): DeviceHardwareSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val hasOpenCl = OPENCL_CANDIDATES.any { File(it).isFile }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }
        return DeviceHardwareSnapshot(
            totalRamBytes = memInfo.totalMem.coerceAtLeast(1L),
            availableRamBytes = memInfo.availMem.coerceAtLeast(0L),
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            hasVulkan = hasVulkan,
            hasOpenCl = hasOpenCl,
            socLabel = soc.orEmpty()
        )
    }

    private val OPENCL_CANDIDATES = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/vendor/lib64/egl/libGLES_mali.so",
        "/system/lib64/libOpenCL.so"
    )
}

enum class BenchmarkModality(val label: String) {
    Chat("对话"),
    Decision("决策"),
    Image("图像"),
    Music("音乐"),
    Video("视频")
}

/**
 * Transparent evidence grading so edge/quantized models without public leaderboard coverage
 * never get fabricated scores.
 */
enum class EvidenceTier(val label: String) {
    PublicLeaderboard("公开独立榜单"),
    OfficialReport("官方技术报告"),
    EdgeReference("端侧专项参考")
}

enum class DeviceFitLevel(val label: String) {
    Smooth("本机流畅"),
    Usable("本机可用"),
    Tight("内存吃紧"),
    LikelyOom("可能内存不足")
}

data class ModelBenchmarkEntry(
    val id: String,
    val familyName: String,
    val modality: BenchmarkModality,
    val paramScale: String,
    val evidenceTier: EvidenceTier,
    val source: String,
    val primaryScoreLabel: String,
    val secondaryScoreLabel: String = "",
    val strengths: String,
    val recommendedQuant: String,
    val minRamGb: Int,
    /** Optional HF repo + search category so users can jump straight to downloading this model. */
    val recommendedRepo: String? = null,
    val searchCategory: HubSearchCategory? = null,
    val aliases: List<String> = emptyList()
)

enum class QuantHintKey {
    Lossless,
    Q8,
    Q6,
    Q5High,
    Q5,
    Q4SweetSpot,
    Q4Compact,
    Q4Fast,
    Q3Balanced,
    Q3Compact,
    Q2Fallback
}

data class QuantQualityHint(
    val tag: String,
    val retentionPercent: Int,
    val note: String,
    val key: QuantHintKey = QuantHintKey.Q4SweetSpot
)

/**
 * High-credibility, source-attributed capability reference for on-device models across all 5 modalities.
 *
 * Design principles:
 * 1. Public leaderboards (Artificial Analysis, VBench, SongEval) evaluate upstream base model families,
 *    not third-party quantized HF repackages — so we map community repos back to their base family.
 * 2. Every score explicitly declares its evidence tier and published source; unmapped repos are
 *    honestly marked "未收录于公开榜单" and paired with HF community signals & device RAM fit instead.
 */
object ModelBenchmarkCatalog {

    val TRUSTED_PACKAGERS = setOf(
        "qwen", "taobao-mnn", "google", "meta-llama", "microsoft", "deepseek-ai",
        "unsloth", "bartowski", "ggml-org", "city96", "quantstack", "leejet",
        "stabilityai", "black-forest-labs", "comfy-org", "tongyi-mai",
        "audio-cpp", "heartmula", "openmoss-team", "baselabs", "hum-ma", "lightx2v",
        "mradermacher", "omnijev", "fastino", "waseigo", "lakonik"
    )

    val entries: List<ModelBenchmarkEntry> = listOf(
        // --- Chat / LLM (ordered by mobile capability tier within practical phone RAM budgets) ---
        ModelBenchmarkEntry(
            id = "qwen3.5-9b",
            familyName = "Qwen3.5-9B",
            modality = BenchmarkModality.Chat,
            paramScale = "9B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Qwen3.5 Technical Report (2026)",
            primaryScoreLabel = "MMLU-Pro 82.5",
            secondaryScoreLabel = "GPQA Diamond 81.7",
            strengths = "混合线性注意力架构，旗舰大内存手机长文与深度思考最强档",
            recommendedQuant = "Q4_K_M (~5.7 GB)",
            minRamGb = 12,
            recommendedRepo = "unsloth/Qwen3.5-9B-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("qwen3.5-9b", "qwen3.5 9b", "qwen3_5-9b", "qwen3-5-9b", "qwen35-9b")
        ),
        ModelBenchmarkEntry(
            id = "inkling-2-mini",
            familyName = "Inkling-2-Mini (MoE)",
            modality = BenchmarkModality.Chat,
            paramScale = "26B Total / ~4B Active (Looped MoE)",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Thinking Machines (2026)",
            primaryScoreLabel = "AA Intelligence ~20",
            secondaryScoreLabel = "1M ctx · Vision · 48 logical layers",
            strengths = "26B-A4B 循环 MoE 旗舰端侧推理与编程能力，智力水平逼近 27B 稠密模型，支持深度思考与识图",
            recommendedQuant = "UD-Q2_K_XL (~9.1 GB) / UD-Q3_K_XL (~13.6 GB)",
            minRamGb = 16,
            recommendedRepo = "unsloth/Inkling-2-Mini-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("inkling-2-mini", "inkling2-mini", "inkling-2-mini-uncensored", "inkling 2 mini")
        ),
        ModelBenchmarkEntry(
            id = "gemma-4-e4b",
            familyName = "Gemma 4 E4B IT",
            modality = BenchmarkModality.Chat,
            paramScale = "4B Effective (Edge)",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Google Gemma 4 Edge Model Card (2026)",
            primaryScoreLabel = "Edge-First Architecture",
            secondaryScoreLabel = "128K ctx · llama.cpp native",
            strengths = "Google 2026 新一代端侧主力模型，多语言理解与长上下文极强，8–12 GB 手机流畅运行",
            recommendedQuant = "Q4_K_M / IQ4_XS (~4.7 GB)",
            minRamGb = 8,
            recommendedRepo = "unsloth/gemma-4-E4B-it-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("gemma-4-e4b", "gemma 4 e4b", "gemma4-e4b", "gemma-4-e4b-it")
        ),
        ModelBenchmarkEntry(
            id = "qwen3-4b",
            familyName = "Qwen3-4B",
            modality = BenchmarkModality.Chat,
            paramScale = "4B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Qwen3 Model Card",
            primaryScoreLabel = "MMLU-Pro 70.4",
            secondaryScoreLabel = "AIME25 65.6 · GPQA 55.9",
            strengths = "4B 级综合推理、中文与代码性价比极高，支持开关深度思考",
            recommendedQuant = "Q4_K_M (~2.5 GB)",
            minRamGb = 8,
            recommendedRepo = "unsloth/Qwen3-4B-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("qwen3-4b", "qwen3 4b", "qwen-3-4b")
        ),
        ModelBenchmarkEntry(
            id = "qwen3.5-4b",
            familyName = "Qwen3.5-4B",
            modality = BenchmarkModality.Chat,
            paramScale = "4B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Qwen3.5 Technical Report (2026)",
            primaryScoreLabel = "MMLU-Pro 69.6",
            secondaryScoreLabel = "GPQA 65.8",
            strengths = "内置 MNN 首选旗舰档，长上下文显存/内存增长平缓",
            recommendedQuant = "MNN 4-bit (~2.8 GB)",
            minRamGb = 8,
            recommendedRepo = "taobao-mnn/Qwen3.5-4B-MNN",
            searchCategory = HubSearchCategory.MnnChat,
            aliases = listOf("qwen3.5-4b", "qwen3.5 4b", "qwen3-5-4b", "qwen35-4b", "qwen3_5-4b")
        ),
        ModelBenchmarkEntry(
            id = "gemma-4-e2b",
            familyName = "Gemma 4 E2B IT",
            modality = BenchmarkModality.Chat,
            paramScale = "2B Effective (Edge)",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Google Gemma 4 Edge Model Card (2026)",
            primaryScoreLabel = "Ultra-Compact Edge 2B",
            secondaryScoreLabel = "128K ctx · ~3.0 GB IQ4",
            strengths = "轻量化新一代 Gemma 4 端侧模型，6–8 GB 内存手机高速离线对话",
            recommendedQuant = "IQ4_XS / Q4_K_M (~3.0 GB)",
            minRamGb = 6,
            recommendedRepo = "unsloth/gemma-4-E2B-it-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("gemma-4-e2b", "gemma 4 e2b", "gemma4-e2b", "gemma-4-e2b-it")
        ),
        ModelBenchmarkEntry(
            id = "deepseek-r1-distill-qwen-1.5b",
            familyName = "DeepSeek-R1-Distill-Qwen-1.5B",
            modality = BenchmarkModality.Chat,
            paramScale = "1.5B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "DeepSeek-R1 Technical Report",
            primaryScoreLabel = "MATH-500 83.9",
            secondaryScoreLabel = "AIME24 28.9",
            strengths = "极轻量数学与逻辑思维链特化，手机端高速推理",
            recommendedQuant = "Q4_K_M (~1.1 GB)",
            minRamGb = 4,
            recommendedRepo = "bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("deepseek-r1-distill-qwen-1.5b", "deepseek-r1-1.5b")
        ),
        ModelBenchmarkEntry(
            id = "qwen3.5-2b",
            familyName = "Qwen3.5-2B",
            modality = BenchmarkModality.Chat,
            paramScale = "2B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Qwen3.5 Technical Report (2026)",
            primaryScoreLabel = "MMLU-Pro 55.3",
            secondaryScoreLabel = "GPQA 51.6",
            strengths = "速度与理解力平衡，日常问答与摘要主力",
            recommendedQuant = "MNN 4-bit (~1.4 GB)",
            minRamGb = 6,
            recommendedRepo = "taobao-mnn/Qwen3.5-2B-MNN",
            searchCategory = HubSearchCategory.MnnChat,
            aliases = listOf("qwen3.5-2b", "qwen3.5 2b", "qwen3-5-2b", "qwen35-2b", "qwen3_5-2b")
        ),
        ModelBenchmarkEntry(
            id = "phi-4-mini",
            familyName = "Phi-4-mini (3.8B)",
            modality = BenchmarkModality.Chat,
            paramScale = "3.8B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Microsoft",
            primaryScoreLabel = "MMLU-Pro 52.8",
            secondaryScoreLabel = "GPQA Diamond 33.1",
            strengths = "英文逻辑、指令遵循与结构化输出扎实",
            recommendedQuant = "Q4_K_M (~2.5 GB)",
            minRamGb = 8,
            recommendedRepo = "bartowski/microsoft_Phi-4-mini-instruct-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("phi-4-mini", "phi4-mini")
        ),
        ModelBenchmarkEntry(
            id = "qwen3-1.7b",
            familyName = "Qwen3-1.7B",
            modality = BenchmarkModality.Chat,
            paramScale = "1.7B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Qwen3 Technical Report",
            primaryScoreLabel = "32K native ctx",
            secondaryScoreLabel = "Dual-mode thinking",
            strengths = "仅约 1.1 GB，中文与工具对话流畅度兼顾",
            recommendedQuant = "Q4_K_M (~1.1 GB)",
            minRamGb = 4,
            recommendedRepo = "unsloth/Qwen3-1.7B-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("qwen3-1.7b", "qwen-3-1.7b")
        ),
        ModelBenchmarkEntry(
            id = "gemma-3-4b",
            familyName = "Gemma 3 4B IT",
            modality = BenchmarkModality.Chat,
            paramScale = "4B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Google",
            primaryScoreLabel = "MMLU-Pro 43.6",
            secondaryScoreLabel = "GPQA 30.8 · 128K ctx",
            strengths = "Google 端侧标杆，128K 超长窗口与多语种均衡",
            recommendedQuant = "Q4_K_M (~2.5 GB)",
            minRamGb = 8,
            recommendedRepo = "unsloth/gemma-3-4b-it-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("gemma-3-4b", "gemma3-4b")
        ),
        ModelBenchmarkEntry(
            id = "qwen2.5-3b",
            familyName = "Qwen2.5-3B-Instruct",
            modality = BenchmarkModality.Chat,
            paramScale = "3B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Open LLM / Qwen2.5 Report",
            primaryScoreLabel = "MMLU 65.6",
            secondaryScoreLabel = "GSM8K 79.1",
            strengths = "成熟稳定、无思考标签开销、中英日常回复快",
            recommendedQuant = "Q4_K_M (~1.9 GB)",
            minRamGb = 6,
            recommendedRepo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("qwen2.5-3b", "qwen2-5-3b")
        ),
        ModelBenchmarkEntry(
            id = "llama-3.2-3b",
            familyName = "Llama 3.2 3B Instruct",
            modality = BenchmarkModality.Chat,
            paramScale = "3B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Meta",
            primaryScoreLabel = "MMLU-Pro 35.4",
            secondaryScoreLabel = "128K ctx",
            strengths = "Meta 端侧高生态兼容模型，英文总结与改写迅速",
            recommendedQuant = "Q4_K_M (~2.0 GB)",
            minRamGb = 6,
            recommendedRepo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("llama-3.2-3b", "llama3.2-3b")
        ),
        ModelBenchmarkEntry(
            id = "qwen3.5-0.8b",
            familyName = "Qwen3.5-0.8B",
            modality = BenchmarkModality.Chat,
            paramScale = "0.8B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Qwen3.5 Technical Report (2026)",
            primaryScoreLabel = "MMLU-Pro 29.7",
            secondaryScoreLabel = "~540 MB ultra-fast",
            strengths = "几乎所有手机秒级加载，适合轻量对话与离线备忘",
            recommendedQuant = "MNN 4-bit (~540 MB)",
            minRamGb = 4,
            recommendedRepo = "taobao-mnn/Qwen3.5-0.8B-MNN",
            searchCategory = HubSearchCategory.MnnChat,
            aliases = listOf("qwen3.5-0.8b", "qwen35-0.8b")
        ),
        ModelBenchmarkEntry(
            id = "qwen3-0.6b",
            familyName = "Qwen3-0.6B",
            modality = BenchmarkModality.Chat,
            paramScale = "0.6B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Qwen3 Technical Report",
            primaryScoreLabel = "0.6B Ultra-Light",
            secondaryScoreLabel = "Q4_K_M ~397 MB",
            strengths = "实测手机端 70 tok/s 极速响应，适合快速体验 GGUF 引擎",
            recommendedQuant = "Q4_K_M (~397 MB)",
            minRamGb = 3,
            recommendedRepo = "unsloth/Qwen3-0.6B-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("qwen3-0.6b", "qwen-3-0.6b")
        ),
        ModelBenchmarkEntry(
            id = "gemma-3-1b",
            familyName = "Gemma 3 1B IT",
            modality = BenchmarkModality.Chat,
            paramScale = "1B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Google Gemma 3 Model Card",
            primaryScoreLabel = "MMLU-Pro 14.7",
            secondaryScoreLabel = "32K ctx · ~800 MB",
            strengths = "超低功耗轻量模型，英文与短文本处理快",
            recommendedQuant = "Q4_K_M (~800 MB)",
            minRamGb = 4,
            recommendedRepo = "unsloth/gemma-3-1b-it-GGUF",
            searchCategory = HubSearchCategory.GgufChat,
            aliases = listOf("gemma-3-1b", "gemma3-1b")
        ),

        // --- Decision ---
        ModelBenchmarkEntry(
            id = "elephant-1-9b",
            familyName = "Elephant-1 9B",
            modality = BenchmarkModality.Decision,
            paramScale = "9B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis Decision Index v2.1",
            primaryScoreLabel = "Decision Index 66.4",
            secondaryScoreLabel = "Accuracy 45.7 · 256 slots",
            strengths = "专精结构化判断/多选/打分，块双向全注意力 + 共享前缀 KV 缓存",
            recommendedQuant = "Q4_K_M (~5.6 GB)",
            minRamGb = 12,
            recommendedRepo = "mradermacher/elephant-1-GGUF",
            searchCategory = HubSearchCategory.DecisionGguf,
            aliases = listOf("elephant-1", "elephant-9b", "elephant 9b")
        ),
        ModelBenchmarkEntry(
            id = "onejev-1.6-4b",
            familyName = "OneJev 1.6 4B",
            modality = BenchmarkModality.Decision,
            paramScale = "4B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Decision Index 0.3 / OmniJev (Apache-2.0)",
            primaryScoreLabel = "Decision Index 62.0",
            secondaryScoreLabel = "Qwen3.5-4B · qev-labels-v2",
            strengths = "4B 开源 System-One 决策标杆，单次前向校准概率，8 GB 手机流畅运行",
            recommendedQuant = "Q4_K_M (~2.5 GB)",
            minRamGb = 8,
            recommendedRepo = "mradermacher/OneJev1.6-4B-GGUF",
            searchCategory = HubSearchCategory.DecisionGguf,
            aliases = listOf("onejev1.6-4b", "onejev-1.6-4b", "onejev-4b")
        ),
        ModelBenchmarkEntry(
            id = "onejev-1.6-0.8b",
            familyName = "OneJev 1.6 0.8B",
            modality = BenchmarkModality.Decision,
            paramScale = "0.8B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "OmniJev / Decision Index (Apache-2.0)",
            primaryScoreLabel = "0.8B Ultra-Light",
            secondaryScoreLabel = "Q4_K_M ~500 MB",
            strengths = "超轻量端侧决策模型，毫秒级分类/判断/打分，任意手机即装即用",
            recommendedQuant = "Q4_K_M (~500 MB)",
            minRamGb = 4,
            recommendedRepo = "mradermacher/OneJev1.6-0.8B-GGUF",
            searchCategory = HubSearchCategory.DecisionGguf,
            aliases = listOf("onejev1.6-0.8b", "onejev-1.6-0.8b", "onejev-0.8b")
        ),

        // --- Image ---
        ModelBenchmarkEntry(
            id = "qwen-image-2.1",
            familyName = "Qwen-Image 2.1",
            modality = BenchmarkModality.Image,
            paramScale = "20B DiT (4-bit)",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis Image Arena",
            primaryScoreLabel = "AA ELO ~1048 (#2 Open)",
            secondaryScoreLabel = "T2I + 2-ref Editing",
            strengths = "画质、构图与中文字体渲染极强，支持 MNN OpenCL 本机加速与图片编辑",
            recommendedQuant = "MNN 4-bit (~15 GB)",
            minRamGb = 16,
            aliases = listOf("qwen-image", "qwen_image")
        ),
        ModelBenchmarkEntry(
            id = "flux3-image-klein",
            familyName = "FLUX.3 Image Klein",
            modality = BenchmarkModality.Image,
            paramScale = "DiT + Qwen3-VL 4B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Black Forest Labs",
            primaryScoreLabel = "AA ELO ~1039 (#3 Open)",
            secondaryScoreLabel = "16-step · T2I + Editing",
            strengths = "2026 新一代紧凑流匹配旗舰，Q4 仅 ~3.7 GB，支持高质量文字渲染与参考图编辑",
            recommendedQuant = "Q4_K_M (~3.7 GB + shared)",
            minRamGb = 12,
            recommendedRepo = "unsloth/flux-3-image-klein-GGUF",
            searchCategory = HubSearchCategory.SdImage,
            aliases = listOf("flux-3-image-klein", "flux3-image-klein", "flux 3 image klein", "flux.3-image-klein")
        ),
        ModelBenchmarkEntry(
            id = "ernie-image-turbo",
            familyName = "ERNIE-Image-Turbo",
            modality = BenchmarkModality.Image,
            paramScale = "8B DiT + Ministral 3B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Baidu ERNIE-Image / stable-diffusion.cpp",
            primaryScoreLabel = "8-step Turbo · CFG 1.0",
            secondaryScoreLabel = "Bilingual CJK/EN Text",
            strengths = "8 步极速出图，中英文海报文字与写实细节表现出色，低显存/内存友好",
            recommendedQuant = "Q4_K_M (~5.0 GB + shared)",
            minRamGb = 12,
            recommendedRepo = "unsloth/ERNIE-Image-Turbo-GGUF",
            searchCategory = HubSearchCategory.SdImage,
            aliases = listOf("ernie-image-turbo", "ernie-image", "ernie_image")
        ),
        ModelBenchmarkEntry(
            id = "z-image-turbo",
            familyName = "Z-Image-Turbo",
            modality = BenchmarkModality.Image,
            paramScale = "6B DiT + Qwen3-4B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Tongyi",
            primaryScoreLabel = "AA ELO ~936 (Open)",
            secondaryScoreLabel = "8-step · CFG 1.0",
            strengths = "手机端体积/画质比最惊艳的开源新一代模型，中英文排版清晰",
            recommendedQuant = "Q4_0 / Q4_K (~3.7 GB + shared)",
            minRamGb = 12,
            recommendedRepo = "unsloth/Z-Image-Turbo-GGUF",
            searchCategory = HubSearchCategory.SdImage,
            aliases = listOf("z-image-turbo", "z_image_turbo", "z-image")
        ),
        ModelBenchmarkEntry(
            id = "flux2-klein-9b",
            familyName = "FLUX.2 Klein 9B",
            modality = BenchmarkModality.Image,
            paramScale = "9B DiT + Qwen3-8B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Black Forest Labs / stable-diffusion.cpp",
            primaryScoreLabel = "4-step · T2I + Editing",
            secondaryScoreLabel = "9B High-Detail Tier",
            strengths = "4 步高细节写实与参考图编辑，适合 16 GB+ 旗舰大内存手机",
            recommendedQuant = "Q4_0 / Q4_K (~5.6 GB + shared)",
            minRamGb = 16,
            recommendedRepo = "unsloth/FLUX.2-klein-9B-GGUF",
            searchCategory = HubSearchCategory.SdImage,
            aliases = listOf("flux.2-klein-9b", "flux-2-klein-9b", "flux2-klein-9b")
        ),
        ModelBenchmarkEntry(
            id = "flux2-klein-4b",
            familyName = "FLUX.2 Klein 4B",
            modality = BenchmarkModality.Image,
            paramScale = "4B DiT + Qwen3-4B",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Black Forest Labs",
            primaryScoreLabel = "AA ELO ~840 (Open)",
            secondaryScoreLabel = "4-step · ~2.5 GB Q4",
            strengths = "紧凑 4B 流匹配架构，4 步即可出高审美写实画面",
            recommendedQuant = "Q4_K_M (~2.6 GB + shared)",
            minRamGb = 10,
            recommendedRepo = "unsloth/FLUX.2-klein-4B-GGUF",
            searchCategory = HubSearchCategory.SdImage,
            aliases = listOf("flux.2-klein-4b", "flux-2-klein-4b", "flux2-klein-4b")
        ),
        ModelBenchmarkEntry(
            id = "moss-image",
            familyName = "MOSS-Image SFT",
            modality = BenchmarkModality.Image,
            paramScale = "DiT + Qwen3.5-9B",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "OpenMOSS-Team",
            primaryScoreLabel = "Region Layout Control",
            secondaryScoreLabel = "Visual Layout Editor",
            strengths = "支持多区域坐标框构图与复杂指令遵循",
            recommendedQuant = "Q4_0 (~9.2 GB full)",
            minRamGb = 16,
            aliases = listOf("moss-image", "moss_image")
        ),
        ModelBenchmarkEntry(
            id = "sdxl-turbo",
            familyName = "SDXL-Turbo",
            modality = BenchmarkModality.Image,
            paramScale = "3.5B single-file",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis / Stability AI",
            primaryScoreLabel = "1–4 step Single-File",
            secondaryScoreLabel = "No external encoder",
            strengths = "零依赖单文件权重，出图速度快，生态成熟",
            recommendedQuant = "FP16 / GGUF (~2.5–6.9 GB)",
            minRamGb = 8,
            recommendedRepo = "stabilityai/sdxl-turbo",
            searchCategory = HubSearchCategory.SdImage,
            aliases = listOf("sdxl-turbo", "sdxl")
        ),
        ModelBenchmarkEntry(
            id = "sd-turbo-15",
            familyName = "SD-Turbo / DreamShaper (SD 1.5)",
            modality = BenchmarkModality.Image,
            paramScale = "0.9B single-file",
            evidenceTier = EvidenceTier.EdgeReference,
            source = "Community Mobile Baseline",
            primaryScoreLabel = "Low-RAM Friendly",
            secondaryScoreLabel = "~2.0 GB single file",
            strengths = "6–8 GB 内存手机也能稳定出图，风格化选择丰富",
            recommendedQuant = "FP16 (~2.0 GB)",
            minRamGb = 6,
            recommendedRepo = "Lykon/DreamShaper",
            searchCategory = HubSearchCategory.SdImage,
            aliases = listOf("sd-turbo", "dreamshaper", "stable-diffusion-v1-5")
        ),

        // --- Music ---
        ModelBenchmarkEntry(
            id = "ace-step-1.5",
            familyName = "ACE-Step 1.5 Turbo",
            modality = BenchmarkModality.Music,
            paramScale = "DiT + 1.7B Planner",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "ACE-Step 1.5 Report / SongEval",
            primaryScoreLabel = "8-step Flow · 50+ langs",
            secondaryScoreLabel = "Vocals & Vulkan GPU",
            strengths = "生成速度远快于纯自回归架构，支持 Vulkan GPU 加速，单文件 GGUF",
            recommendedQuant = "Q8_0 (~6.2 GB)",
            minRamGb = 12,
            recommendedRepo = "audio-cpp/audio.cpp-gguf",
            searchCategory = HubSearchCategory.MusicAudio,
            aliases = listOf("ace-step", "acestep")
        ),
        ModelBenchmarkEntry(
            id = "yue2-3b",
            familyName = "YuE2-3B",
            modality = BenchmarkModality.Music,
            paramScale = "3B + Oobleck VAE",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "M-A-P YuE2 / audio.cpp",
            primaryScoreLabel = "ABC Score CoT",
            secondaryScoreLabel = "48 kHz · ~2.9 GB",
            strengths = "体积紧凑（Q4_0 约 2.9 GB），可输出可读 ABC 乐谱与完整人声段落",
            recommendedQuant = "Q4_0 (~2.9 GB)",
            minRamGb = 8,
            recommendedRepo = "audio-cpp/Yue2-3B-GGUF",
            searchCategory = HubSearchCategory.MusicAudio,
            aliases = listOf("yue2", "yue-2")
        ),
        ModelBenchmarkEntry(
            id = "heartmula-3b",
            familyName = "HeartMuLa 3B",
            modality = BenchmarkModality.Music,
            paramScale = "3B + HeartCodec",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "HeartMuLa (Apache-2.0)",
            primaryScoreLabel = "Multilingual Lyrics",
            secondaryScoreLabel = "Apache-2.0 License",
            strengths = "结构标签与风格词响应细腻，开源协议宽松，单文件 GGUF",
            recommendedQuant = "Q8_0 (~7.7 GB)",
            minRamGb = 12,
            recommendedRepo = "audio-cpp/audio.cpp-gguf",
            searchCategory = HubSearchCategory.MusicAudio,
            aliases = listOf("heartmula")
        ),
        ModelBenchmarkEntry(
            id = "stable-audio-3-small",
            familyName = "Stable Audio 3 Small",
            modality = BenchmarkModality.Music,
            paramScale = "Light Diffusion",
            evidenceTier = EvidenceTier.OfficialReport,
            source = "Stability AI / audio.cpp",
            primaryScoreLabel = "~1.68 GB Fast Audio/SFX",
            secondaryScoreLabel = "8-step · Low RAM",
            strengths = "纯音乐、氛围配乐与环境音效生成极快，普通手机即可流畅运行",
            recommendedQuant = "Q8_0 (~1.68 GB)",
            minRamGb = 6,
            recommendedRepo = "audio-cpp/audio.cpp-gguf",
            searchCategory = HubSearchCategory.MusicAudio,
            aliases = listOf("stable-audio", "stable_audio")
        ),

        // --- Video ---
        ModelBenchmarkEntry(
            id = "minimax-h3-pih3",
            familyName = "MiniMax-H3 · pi-H3 (2–4 Step AV)",
            modality = BenchmarkModality.Video,
            paramScale = "17B Pruned DiT + Qwen3-VL",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis Video Arena / pi-Flow",
            primaryScoreLabel = "AA Video ELO 1163 (#1 Open)",
            secondaryScoreLabel = "2–4 Step Joint Video+Audio · 24 fps",
            strengths = "原生音画同步视频生成，搭配 pi-H3 仅需 2–4 步去噪，通过磁盘分段流式加载可在 12 GB+ 手机运行（需约 18 GB 存储空间）",
            recommendedQuant = "Q2_K / Q3_K (~6.3–8.2 GB + shared)",
            minRamGb = 12,
            recommendedRepo = "unsloth/MiniMax-H3-GGUF",
            searchCategory = HubSearchCategory.VideoWan,
            aliases = listOf("minimax-h3", "minimax_h3", "minimax h3", "pi-h3", "pi_h3")
        ),
        ModelBenchmarkEntry(
            id = "wan2.2-ti2v-5b",
            familyName = "Wan2.2-TI2V-5B (Turbo / Base)",
            modality = BenchmarkModality.Video,
            paramScale = "5B DiT + UMT5-XXL",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "Artificial Analysis Video Arena / VBench 83+",
            primaryScoreLabel = "Top Mobile Open T2V/I2V",
            secondaryScoreLabel = "4-step Turbo · 480×832",
            strengths = "当前手机端画质与物理动态最强的单 DiT 开源视频模型，Turbo 4 步可跑",
            recommendedQuant = "Q4_0 (~3.0 GB + shared)",
            minRamGb = 12,
            recommendedRepo = "hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF",
            searchCategory = HubSearchCategory.VideoWan,
            aliases = listOf("wan2.2-ti2v-5b", "wan2_2-ti2v-5b", "wan-2.2-5b")
        ),
        ModelBenchmarkEntry(
            id = "wan2.1-t2v-1.3b",
            familyName = "Wan2.1-T2V-1.3B",
            modality = BenchmarkModality.Video,
            paramScale = "1.3B DiT + UMT5-XXL",
            evidenceTier = EvidenceTier.PublicLeaderboard,
            source = "VBench / Wan2.1 Report",
            primaryScoreLabel = "VBench ~83.3 (1.3B)",
            secondaryScoreLabel = "2–3× faster than 5B",
            strengths = "体积极小（Q4 仅 ~0.9 GB），8–12 GB 内存手机首选文生视频方案",
            recommendedQuant = "Q4_K_M / Q8_0 (~0.9–1.5 GB)",
            minRamGb = 8,
            recommendedRepo = "samuelchristlie/Wan2.1-T2V-1.3B-GGUF",
            searchCategory = HubSearchCategory.VideoWan,
            aliases = listOf("wan2.1-t2v-1.3b", "wan2.1_t2v_1.3b", "wan21-1.3b")
        )
    )

    fun byModality(modality: BenchmarkModality): List<ModelBenchmarkEntry> =
        entries.filter { it.modality == modality }

    /** Maps a community HF repo slug or model name back to its canonical upstream base model benchmark entry. */
    fun matchRepoOrName(repoOrName: String): ModelBenchmarkEntry? {
        val norm = repoOrName.lowercase(Locale.ROOT)
        // Longest alias match wins so "qwen3.5-4b" matches before "qwen3-4b" and "z-image-turbo" before "z-image".
        return entries
            .flatMap { entry -> entry.aliases.map { alias -> alias to entry } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (alias, _) -> norm.contains(alias) }
            ?.second
    }

    fun isTrustedPackager(author: String): Boolean =
        author.lowercase(Locale.ROOT) in TRUSTED_PACKAGERS

    /** Empirical perplexity/quality retention reference for GGUF quant levels. */
    fun quantQualityHint(quantTag: String?): QuantQualityHint? {
        val raw = quantTag?.uppercase(Locale.ROOT) ?: return null
        val tag = raw.removePrefix("UD-")
        return when {
            tag == "F16" || tag == "BF16" || tag == "FP16" || tag == "FP32" ->
                QuantQualityHint(tag, 100, "无损原精度，体积最大", QuantHintKey.Lossless)
            tag.startsWith("Q8") ->
                QuantQualityHint(tag, 99, "几乎无损，大内存首选", QuantHintKey.Q8)
            tag.startsWith("Q6") ->
                QuantQualityHint(tag, 98, "极低折损，画质/推理极佳", QuantHintKey.Q6)
            tag.startsWith("Q5_K_M") || tag.startsWith("Q5_K_L") || tag.startsWith("Q5_K_XL") || tag == "Q5_1" ->
                QuantQualityHint(tag, 97, "高质量均衡档", QuantHintKey.Q5High)
            tag.startsWith("Q5") ->
                QuantQualityHint(tag, 96, "高质量偏轻档", QuantHintKey.Q5)
            tag == "Q4_K_M" || tag == "Q4_K_L" || tag == "Q4_K_XL" || tag == "Q4_K" ->
                QuantQualityHint(tag, 94, "端侧黄金甜点，速度与精度最均衡", QuantHintKey.Q4SweetSpot)
            tag == "IQ4_XS" || tag == "IQ4_NL" || tag == "Q4_K_S" ->
                QuantQualityHint(tag, 92, "紧凑 4-bit，省内存推荐", QuantHintKey.Q4Compact)
            tag == "Q4_0" || tag == "Q4_1" ->
                QuantQualityHint(tag, 91, "ARM dotprod 原生最快，略有折损", QuantHintKey.Q4Fast)
            tag.startsWith("Q3_K_M") || tag.startsWith("Q3_K_L") || tag.startsWith("Q3_K_XL") || tag == "Q3_K" ->
                QuantQualityHint(tag, 87, "3-bit 压缩，复杂推理略有下降", QuantHintKey.Q3Balanced)
            tag.startsWith("Q3") || tag.startsWith("IQ3") ->
                QuantQualityHint(tag, 84, "高压缩 3-bit，适合小内存尝鲜", QuantHintKey.Q3Compact)
            tag == "Q2_K_XL" ->
                QuantQualityHint(tag, 80, "动态 2-bit 强化，兼顾极限体积与可用性", QuantHintKey.Q2Fallback)
            tag.startsWith("Q2") || tag.startsWith("IQ2") ->
                QuantQualityHint(tag, 75, "极限 2-bit，精度折损明显，仅建议兜底", QuantHintKey.Q2Fallback)
            else -> null
        }
    }

    /** Evaluates how comfortably a model of [modelBytes] will fit in this device's RAM. */
    fun evaluateDeviceFit(modelBytes: Long, device: DeviceHardwareSnapshot?): DeviceFitLevel {
        if (device == null || device.totalRamBytes <= 0L) return DeviceFitLevel.Usable
        val estimatedRuntimeBytes = (modelBytes * 1.35 + 1_200_000_000L).toLong()
        val ratio = estimatedRuntimeBytes.toDouble() / device.totalRamBytes.toDouble()
        return when {
            ratio <= 0.45 -> DeviceFitLevel.Smooth
            ratio <= 0.68 -> DeviceFitLevel.Usable
            ratio <= 0.85 -> DeviceFitLevel.Tight
            else -> DeviceFitLevel.LikelyOom
        }
    }
}
