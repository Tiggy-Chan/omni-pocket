package com.tiggy.qwenimage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class HubSearchCategory(val label: String, val hint: String) {
    GgufChat("GGUF 对话", "搜索 GGUF 语言模型，如 Qwen3、Gemma、Llama"),
    MnnChat("MNN 对话", "搜索 MNN 转换包，如 taobao-mnn"),
    SdImage("图像模型", "搜索 SD / SDXL / Turbo 单文件权重")
}

enum class HfFileFormat { Gguf, Safetensors, Ckpt, MnnBundle }

data class HfRepoSummary(
    val id: String,
    val author: String,
    val name: String,
    val downloads: Long,
    val likes: Int,
    val pipelineTag: String,
    val tags: List<String>,
    val gated: Boolean,
    val summary: String = ""
)

data class HfDownloadFile(
    val path: String,
    val size: Long,
    val sha256: String?
)

data class HfRepoOption(
    val id: String,
    val label: String,
    val repo: String,
    val revision: String,
    val type: HubModelType,
    val format: HfFileFormat,
    val entryFile: String,
    val files: List<HfDownloadFile>,
    val totalBytes: Long,
    val quantTag: String?,
    val splitWarning: String? = null
) {
    /** Approximate minimum device RAM to load and run cleanly on Android. */
    val recommendedRamGb: Int
        get() = ((totalBytes / 1_000_000_000.0) * 1.35 + 1.5).toInt().coerceAtLeast(3)
}

object HuggingFaceHubClient {
    suspend fun search(
        query: String,
        category: HubSearchCategory,
        settings: HubSettings,
        limit: Int = 24
    ): List<HfRepoSummary> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val base = settings.effectiveBaseUrl
        val params = buildList {
            add("limit=$limit")
            add("sort=downloads")
            add("direction=-1")
            when (category) {
                HubSearchCategory.GgufChat -> {
                    add("filter=gguf")
                    if (trimmed.isNotEmpty()) add("search=${HubUrlRewriter.encodeSegment(trimmed)}")
                }
                HubSearchCategory.MnnChat -> {
                    if (trimmed.isEmpty()) {
                        add("author=taobao-mnn")
                    } else {
                        val effective = if (trimmed.contains("mnn", ignoreCase = true)) trimmed else "$trimmed MNN"
                        add("search=${HubUrlRewriter.encodeSegment(effective)}")
                    }
                }
                HubSearchCategory.SdImage -> {
                    add("pipeline_tag=text-to-image")
                    if (trimmed.isNotEmpty()) add("search=${HubUrlRewriter.encodeSegment(trimmed)}")
                }
            }
        }
        val url = "$base/api/models?${params.joinToString("&")}"
        val body = ResumableHttpDownloader.fetchText(url, settings)
        parseSearchResults(body, category)
    }

    suspend fun listDownloadableOptions(
        repo: String,
        category: HubSearchCategory,
        settings: HubSettings,
        revision: String = "main"
    ): List<HfRepoOption> = withContext(Dispatchers.IO) {
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "仓库格式应为 owner/repo" }
        val base = settings.effectiveBaseUrl
        val url = "$base/api/models/$repo/tree/${HubUrlRewriter.encodeSegment(revision)}?recursive=true"
        val body = ResumableHttpDownloader.fetchText(url, settings, maxBytes = 4 * 1024 * 1024)
        parseRepoTree(repo, revision, body, category)
    }

    internal fun parseSearchResults(jsonText: String, category: HubSearchCategory): List<HfRepoSummary> {
        val array = JSONArray(jsonText)
        val results = mutableListOf<HfRepoSummary>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { item.optString("modelId") }
            if (!id.contains('/')) continue
            val tagsArray = item.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagsArray.length()).mapNotNull { tagsArray.optString(it).takeIf(String::isNotBlank) }
            val gatedValue = item.opt("gated")
            val gated = when (gatedValue) {
                is Boolean -> gatedValue
                is String -> gatedValue.isNotBlank() && !gatedValue.equals("false", ignoreCase = true)
                else -> false
            }
            val author = id.substringBefore('/')
            val name = id.substringAfter('/')
            if (category == HubSearchCategory.MnnChat && !id.contains("mnn", ignoreCase = true)) continue
            results += HfRepoSummary(
                id = id,
                author = author,
                name = name,
                downloads = item.optLong("downloads", 0L),
                likes = item.optInt("likes", 0),
                pipelineTag = item.optString("pipeline_tag"),
                tags = tags,
                gated = gated
            )
        }
        return results
    }

    internal fun parseRepoTree(
        repo: String,
        revision: String,
        jsonText: String,
        category: HubSearchCategory
    ): List<HfRepoOption> {
        val array = JSONArray(jsonText)
        val files = mutableListOf<HfDownloadFile>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") != "file") continue
            val path = item.optString("path")
            if (path.isBlank() || path.startsWith('.') || path.contains("..")) continue
            val lfs = item.optJSONObject("lfs")
            val size = lfs?.optLong("size", 0L)?.takeIf { it > 0L } ?: item.optLong("size", 0L)
            val sha256 = lfs?.optString("oid")?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            files += HfDownloadFile(path = path, size = size, sha256 = sha256)
        }

        return when (category) {
            HubSearchCategory.GgufChat -> extractGgufChatOptions(repo, revision, files)
            HubSearchCategory.MnnChat -> extractMnnChatOptions(repo, revision, files)
            HubSearchCategory.SdImage -> extractSdImageOptions(repo, revision, files)
        }
    }

    private fun extractGgufChatOptions(
        repo: String,
        revision: String,
        files: List<HfDownloadFile>
    ): List<HfRepoOption> {
        val ggufs = files.filter { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            lower.endsWith(".gguf") && !lower.contains("mmproj") && !lower.contains("imatrix")
        }
        return ggufs.map { file ->
            val fileName = file.path.substringAfterLast('/')
            val isSplit = SPLIT_GGUF_REGEX.containsMatchIn(fileName)
            val quant = extractQuantTag(fileName)
            HfRepoOption(
                id = sanitizeModelId("gguf", repo, file.path),
                label = fileName,
                repo = repo,
                revision = revision,
                type = HubModelType.GgufChat,
                format = HfFileFormat.Gguf,
                entryFile = file.path,
                files = listOf(file),
                totalBytes = file.size,
                quantTag = quant,
                splitWarning = if (isSplit) "分片 GGUF 文件暂不支持单文件直接加载，请选择单文件版本" else null
            )
        }.sortedWith(compareBy<HfRepoOption> { it.splitWarning != null }.thenBy { quantPriority(it.quantTag) }.thenBy { it.totalBytes })
    }

    private fun extractMnnChatOptions(
        repo: String,
        revision: String,
        files: List<HfDownloadFile>
    ): List<HfRepoOption> {
        val byPath = files.associateBy { it.path }
        val coreRequired = listOf("config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt")
        if (!coreRequired.all { it in byPath }) return emptyList()
        val optional = listOf("visual.mnn", "visual.mnn.weight", "embeddings_bf16.bin", "embeddings_int4.bin", "embeddings_int8.bin")
        val bundle = (coreRequired + optional).mapNotNull { byPath[it] }
        return listOf(
            HfRepoOption(
                id = sanitizeModelId("mnn", repo, "bundle"),
                label = "${repo.substringAfter('/')} (MNN 完整包)",
                repo = repo,
                revision = revision,
                type = HubModelType.MnnChat,
                format = HfFileFormat.MnnBundle,
                entryFile = "config.json",
                files = bundle,
                totalBytes = bundle.sumOf { it.size },
                quantTag = "MNN"
            )
        )
    }

    private fun extractSdImageOptions(
        repo: String,
        revision: String,
        files: List<HfDownloadFile>
    ): List<HfRepoOption> {
        val candidates = files.filter { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            val name = lower.substringAfterLast('/')
            val validExt = lower.endsWith(".safetensors") || lower.endsWith(".gguf") || lower.endsWith(".ckpt")
            if (!validExt) return@filter false
            // Exclude diffusers sub-component folders (unet/, vae/, text_encoder/) and tiny LoRA/embedding files.
            val isComponentSubdir = lower.startsWith("unet/") || lower.startsWith("vae/") ||
                lower.startsWith("text_encoder") || lower.startsWith("safety_checker/") ||
                lower.startsWith("transformer/") || lower.startsWith("tokenizer/")
            val looksAuxiliary = name.contains("vae") || name.contains("lora") || name.contains("clip") ||
                name.contains("t5") || name.contains("mmproj") || name.contains("controlnet") ||
                name.contains("embedding")
            !isComponentSubdir && !looksAuxiliary && file.size >= 400_000_000L
        }
        return candidates.map { file ->
            val fileName = file.path.substringAfterLast('/')
            val format = when {
                fileName.endsWith(".gguf", ignoreCase = true) -> HfFileFormat.Gguf
                fileName.endsWith(".ckpt", ignoreCase = true) -> HfFileFormat.Ckpt
                else -> HfFileFormat.Safetensors
            }
            HfRepoOption(
                id = sanitizeModelId("sd", repo, file.path),
                label = fileName,
                repo = repo,
                revision = revision,
                type = HubModelType.SdImage,
                format = format,
                entryFile = file.path,
                files = listOf(file),
                totalBytes = file.size,
                quantTag = extractQuantTag(fileName) ?: if (fileName.contains("fp16", ignoreCase = true)) "FP16" else null
            )
        }.sortedBy { it.totalBytes }
    }

    fun curatedRecommendations(category: HubSearchCategory): List<HfRepoSummary> = when (category) {
        HubSearchCategory.GgufChat -> listOf(
            HfRepoSummary("Qwen/Qwen2.5-1.5B-Instruct-GGUF", "Qwen", "Qwen2.5-1.5B-Instruct-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen2"), false, "轻量中英双语，手机端速度极快"),
            HfRepoSummary("Qwen/Qwen2.5-3B-Instruct-GGUF", "Qwen", "Qwen2.5-3B-Instruct-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen2"), false, "均衡推荐，中英问答与总结表现优秀"),
            HfRepoSummary("Qwen/Qwen3-1.7B-GGUF", "Qwen", "Qwen3-1.7B-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen3"), false, "支持深度思考模式的新一代小模型"),
            HfRepoSummary("Qwen/Qwen3-4B-GGUF", "Qwen", "Qwen3-4B-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen3"), false, "推理与中文能力强，建议 8 GB+ 内存"),
            HfRepoSummary("bartowski/Llama-3.2-3B-Instruct-GGUF", "bartowski", "Llama-3.2-3B-Instruct-GGUF", 0, 0, "text-generation", listOf("gguf", "llama"), false, "Meta 轻量端侧对话模型"),
            HfRepoSummary("unsloth/gemma-3-1b-it-GGUF", "unsloth", "gemma-3-1b-it-GGUF", 0, 0, "text-generation", listOf("gguf", "gemma3"), false, "Google Gemma 3 1B 轻量对话模型"),
            HfRepoSummary("bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", "bartowski", "DeepSeek-R1-Distill-Qwen-1.5B-GGUF", 0, 0, "text-generation", listOf("gguf", "deepseek"), false, "DeepSeek R1 1.5B 蒸馏思考模型")
        )
        HubSearchCategory.MnnChat -> listOf(
            HfRepoSummary("taobao-mnn/Qwen3-1.7B-MNN", "taobao-mnn", "Qwen3-1.7B-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 Qwen3 1.7B"),
            HfRepoSummary("taobao-mnn/Qwen3-4B-MNN", "taobao-mnn", "Qwen3-4B-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 Qwen3 4B"),
            HfRepoSummary("taobao-mnn/DeepSeek-R1-1.5B-Qwen-MNN", "taobao-mnn", "DeepSeek-R1-1.5B-Qwen-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 DeepSeek-R1 1.5B"),
            HfRepoSummary("taobao-mnn/Qwen2.5-3B-Instruct-MNN", "taobao-mnn", "Qwen2.5-3B-Instruct-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 Qwen2.5 3B")
        )
        HubSearchCategory.SdImage -> listOf(
            HfRepoSummary("stabilityai/sdxl-turbo", "stabilityai", "sdxl-turbo", 0, 0, "text-to-image", listOf("sdxl", "turbo"), false, "1–4 步快速出图，单文件 safetensors"),
            HfRepoSummary("stabilityai/sd-turbo", "stabilityai", "sd-turbo", 0, 0, "text-to-image", listOf("sd2", "turbo"), false, "轻量快速文生图，内存占用低"),
            HfRepoSummary("Lykon/DreamShaper", "Lykon", "DreamShaper", 0, 0, "text-to-image", listOf("sd15"), false, "经典 SD 1.5 风格化通用生图模型"),
            HfRepoSummary("stable-diffusion-v1-5/stable-diffusion-v1-5", "stable-diffusion-v1-5", "stable-diffusion-v1-5", 0, 0, "text-to-image", listOf("sd15"), false, "SD 1.5 基础单文件权重")
        )
    }

    internal fun extractQuantTag(fileName: String): String? =
        QUANT_REGEX.find(fileName)?.value?.uppercase(Locale.ROOT)

    internal fun sanitizeModelId(prefix: String, repo: String, path: String): String {
        val raw = "$prefix-${repo.replace('/', '-')}-${path.substringAfterLast('/').substringBeforeLast('.')}"
        val cleaned = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.-]+"), "-").trim('-')
        val hash = Integer.toHexString("$repo:$path".hashCode())
        return if (cleaned.length <= 68) cleaned else "${cleaned.take(58)}-$hash"
    }

    private fun quantPriority(tag: String?): Int = when (tag) {
        "Q4_K_M" -> 0
        "Q4_K_S" -> 1
        "Q4_0" -> 2
        "IQ4_XS" -> 3
        "Q5_K_M" -> 4
        "Q6_K" -> 5
        "Q8_0" -> 6
        else -> 10
    }

    private val QUANT_REGEX = Regex("""(?i)\b(IQ[1-4]_[A-Z0-9]+|Q[2-8]_[01K](?:_[SML])?|F16|BF16|FP16|FP32)\b""")
    private val SPLIT_GGUF_REGEX = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)
}
