package com.tiggy.qwenimage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

enum class HubSearchCategory(val label: String, val hint: String) {
    GgufChat("GGUF 对话", "搜索 GGUF 对话模型，如 Qwen3、Qwen3.5、Gemma 3"),
    MnnChat("MNN 对话", "搜索 MNN 转换包，如 taobao-mnn"),
    DecisionGguf("决策", "搜索决策 GGUF 模型，如 OneJev1.6、elephant-1"),
    SdImage("图像", "搜索 SD / SDXL / Z-Image / FLUX.2 Klein 权重"),
    VideoWan("视频", "搜索 Wan2.2 / Wan2.1 GGUF 视频模型"),
    MusicAudio("音乐", "搜索 ACE-Step / YuE2 / HeartMuLa / Stable Audio GGUF"),
    Embedding("向量嵌入", "搜索知识库嵌入模型，如 bge-small-zh、Qwen3-Embedding")
}

enum class HubSearchSort(val apiValue: String) {
    Downloads("downloads"),
    Likes("likes"),
    LastModified("lastModified")
}

enum class HfFileFormat { Gguf, Safetensors, Ckpt, MnnBundle }

enum class HubSplitWarning {
    SplitGguf,
    WanMoE14B,
    MiniMaxH3Unpruned
}

enum class HubCompanionNote {
    ZImageShared,
    Flux2Shared,
    Flux2Klein9BShared,
    Flux3KleinShared,
    ErnieImageShared,
    WanShared,
    MiniMaxH3PiShared,
    Yue2Sidecars,
    SelfContainedAudioGguf,
    DecisionBlockBidir,
    DecisionCausal
}

data class HfRepoSummary(
    val id: String,
    val author: String,
    val name: String,
    val downloads: Long,
    val likes: Int,
    val pipelineTag: String,
    val tags: List<String>,
    val gated: Boolean,
    val summary: String = "",
    val summaryKey: String? = null
)

data class HfDownloadFile(
    val path: String,
    val size: Long,
    val sha256: String?,
    val sourceRepo: String? = null,
    val sourceRevision: String? = null,
    val sharedCompanionKey: String? = null
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
    val splitWarning: String? = null,
    val companionSummary: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val splitWarningKey: HubSplitWarning? = null,
    val companionNoteKey: HubCompanionNote? = null,
    val companionNoteArg: String? = null,
    /** Effective RAM footprint when weights stream from disk (e.g. MiniMax-H3) instead of loading all files at once. */
    val runtimeRamBytesOverride: Long? = null,
    val recommendedRamGbOverride: Int? = null
) {
    /** Bytes used for on-device RAM fit classification. */
    val runtimeBytesForFit: Long
        get() = runtimeRamBytesOverride ?: totalBytes

    /** Approximate minimum device RAM to load and run cleanly on Android. */
    val recommendedRamGb: Int
        get() = recommendedRamGbOverride
            ?: ((runtimeBytesForFit / 1_000_000_000.0) * 1.35 + 1.5).toInt().coerceAtLeast(3)
}

/** Verified shared companion artifacts reused across modular diffusion/video/audio models. */
object HubCompanionArtifacts {
    val UMT5_XXL_Q3_K_S = HfDownloadFile(
        path = "umt5-xxl-encoder-Q3_K_S.gguf",
        size = 2_858_489_696L,
        sha256 = "f64f8d6dc4d8a24276df69d0ccea789aae686f7417950a41e6568c30cb478a5c",
        sourceRepo = "city96/umt5-xxl-encoder-gguf",
        sourceRevision = "main",
        sharedCompanionKey = "umt5-xxl-encoder-Q3_K_S.gguf"
    )
    val TAE_WAN2_2 = HfDownloadFile(
        path = "taew2_2.safetensors",
        size = 22_848_048L,
        sha256 = "5243c5c9d77ecf2d74800d672bac3678c0d72462899f1b3b10aa1bbc11eae461",
        sourceRepo = "lightx2v/Autoencoders",
        sourceRevision = "main",
        sharedCompanionKey = "taew2_2.safetensors"
    )
    val TAE_WAN2_1 = HfDownloadFile(
        path = "taew2_1.safetensors",
        size = 22_642_902L,
        sha256 = "79482981ab4db9c2ec909f7aae555831e67efe3e7216752411641b90b9600cdb",
        sourceRepo = "lightx2v/Autoencoders",
        sourceRevision = "main",
        sharedCompanionKey = "taew2_1.safetensors"
    )
    val MINIMAX_H3_TE_Q2_K = HfDownloadFile(
        path = "qwen3vl-32B-MiniMax-H3-Q2_K.gguf",
        size = 8_487_968_160L,
        sha256 = "5bbc11d0b3ef197c98df2ce8f05de8fbb8eb5917cd91c33d0b59f93759b34914",
        sourceRepo = "realrebelai/MiniMax-H3_GGUFs",
        sourceRevision = "main",
        sharedCompanionKey = "qwen3vl-32B-MiniMax-H3-Q2_K.gguf"
    )
    val MINIMAX_H3_VIDEO_VAE = HfDownloadFile(
        path = "vae/minimax_h3_video_vae_int8_convrot.safetensors",
        size = 2_811_065_184L,
        sha256 = "52a2c8c73583c86e4f41cdcce3a6ad0ea562987bc0bf3d60a0cef5f5c8e60c0e",
        sourceRepo = "Comfy-Org/MiniMax-H3",
        sourceRevision = "main",
        sharedCompanionKey = "minimax_h3_video_vae_int8_convrot.safetensors"
    )
    val MINIMAX_H3_AUDIO_VAE = HfDownloadFile(
        path = "vae/minimax_h3_audio_vae_fp32.safetensors",
        size = 605_254_808L,
        sha256 = "8e505d95dd1561d47abd43d4238fd40d9bb1ae9e147ed0a4cba778d76ae4db48",
        sourceRepo = "Comfy-Org/MiniMax-H3",
        sourceRevision = "main",
        sharedCompanionKey = "minimax_h3_audio_vae_fp32.safetensors"
    )
    val PI_H3_2STEP_ADAPTER = HfDownloadFile(
        path = "pi_h3_2step_adapter/diffusion_pytorch_model.safetensors",
        size = 503_383_808L,
        sha256 = "2602688b12e2763fdbc1ad7e0c626b83942fbda511050f266a49dbcdc1db5f02",
        sourceRepo = "Lakonik/pi-H3",
        sourceRevision = "main",
        sharedCompanionKey = "pi_h3_2step_adapter.safetensors"
    )
    val PI_H3_4STEP_ADAPTER = HfDownloadFile(
        path = "pi_h3_4step_adapter/diffusion_pytorch_model.safetensors",
        size = 503_383_808L,
        sha256 = "2dcdbb193f08755e0efb5f3b07585a988e30eb1c38c25283d30635ccf3d6e07f",
        sourceRepo = "Lakonik/pi-H3",
        sourceRevision = "main",
        sharedCompanionKey = "pi_h3_4step_adapter.safetensors"
    )
    val FLUX1_AE_VAE = HfDownloadFile(
        path = "split_files/vae/ae.safetensors",
        size = 335_304_388L,
        sha256 = "afc8e28272cd15db3919bacdb6918ce9c1ed22e96cb12c4d5ed0fba823529e38",
        sourceRepo = "Comfy-Org/z_image_turbo",
        sourceRevision = "main",
        sharedCompanionKey = "flux1_ae.safetensors"
    )
    val FLUX2_AE_VAE = HfDownloadFile(
        path = "split_files/vae/flux2-vae.safetensors",
        size = 336_213_556L,
        sha256 = "d64f3a68e1cc4f9f4e29b6e0da38a0204fe9a49f2d4053f0ec1fa1ca02f9c4b5",
        sourceRepo = "Comfy-Org/flux2-dev",
        sourceRevision = "main",
        sharedCompanionKey = "flux2_ae.safetensors"
    )
    val QWEN3_4B_INSTRUCT_2507 = HfDownloadFile(
        path = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        size = 2_497_281_120L,
        sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
        sourceRepo = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
        sourceRevision = "main",
        sharedCompanionKey = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
    )
    val QWEN3_4B_BASE_TE = HfDownloadFile(
        path = "Qwen3-4B-Q4_K_M.gguf",
        size = 2_497_281_312L,
        sha256 = "f6f851777709861056efcdad3af01da38b31223a3ba26e61a4f8bf3a2195813a",
        sourceRepo = "unsloth/Qwen3-4B-GGUF",
        sourceRevision = "main",
        sharedCompanionKey = "Qwen3-4B-Q4_K_M.gguf"
    )
    val QWEN3_8B_BASE_TE = HfDownloadFile(
        path = "Qwen3-8B-Q4_K_M.gguf",
        size = 5_027_784_512L,
        sha256 = "120307ba529eb2439d6c430d94104dabd578497bc7bfe7e322b5d9933b449bd4",
        sourceRepo = "unsloth/Qwen3-8B-GGUF",
        sourceRevision = "main",
        sharedCompanionKey = "Qwen3-8B-Q4_K_M.gguf"
    )
    val QWEN3_VL_4B_INSTRUCT = HfDownloadFile(
        path = "Qwen3VL-4B-Instruct-Q4_K_M.gguf",
        size = 2_497_281_664L,
        sha256 = "66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a",
        sourceRepo = "Qwen/Qwen3-VL-4B-Instruct-GGUF",
        sourceRevision = "main",
        sharedCompanionKey = "Qwen3VL-4B-Instruct-Q4_K_M.gguf"
    )
    val MINISTRAL_3_3B_INSTRUCT = HfDownloadFile(
        path = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        size = 2_146_497_824L,
        sha256 = "fd46fc371ff0509bfa8657ac956b7de8534d7d9baaa4947975c0648c3aa397f4",
        sourceRepo = "unsloth/Ministral-3-3B-Instruct-2512-GGUF",
        sourceRevision = "main",
        sharedCompanionKey = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf"
    )
}

object HuggingFaceHubClient {
    suspend fun search(
        query: String,
        category: HubSearchCategory,
        settings: HubSettings,
        limit: Int = 24,
        sort: HubSearchSort = HubSearchSort.Downloads
    ): List<HfRepoSummary> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val base = settings.effectiveBaseUrl
        val params = buildList {
            add("limit=$limit")
            add("sort=${sort.apiValue}")
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
                HubSearchCategory.DecisionGguf -> {
                    add("filter=gguf")
                    val effective = if (trimmed.isEmpty()) "OneJev" else trimmed
                    add("search=${HubUrlRewriter.encodeSegment(effective)}")
                }
                HubSearchCategory.SdImage -> {
                    val lower = trimmed.lowercase(Locale.ROOT)
                    val wantsGguf = lower.contains("gguf") || lower.contains("z-image") || lower.contains("flux") ||
                        lower.contains("ernie") || lower.contains("qwen") || lower.contains("fibo") || lower.contains("ming")
                    if (!wantsGguf) add("pipeline_tag=text-to-image")
                    if (trimmed.isNotEmpty()) add("search=${HubUrlRewriter.encodeSegment(trimmed)}")
                }
                HubSearchCategory.VideoWan -> {
                    val effective = when {
                        trimmed.isEmpty() -> "Wan2.2 gguf"
                        trimmed.contains("gguf", ignoreCase = true) -> trimmed
                        else -> "$trimmed gguf"
                    }
                    add("search=${HubUrlRewriter.encodeSegment(effective)}")
                }
                HubSearchCategory.MusicAudio -> {
                    if (trimmed.isEmpty()) {
                        add("author=audio-cpp")
                    } else {
                        val effective = if (trimmed.contains("gguf", ignoreCase = true)) trimmed else "$trimmed gguf"
                        add("search=${HubUrlRewriter.encodeSegment(effective)}")
                    }
                }
                HubSearchCategory.Embedding -> {
                    add("filter=gguf")
                    val effective = if (trimmed.isEmpty()) "embedding" else trimmed
                    add("search=${HubUrlRewriter.encodeSegment(effective)}")
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
        val body = ResumableHttpDownloader.fetchText(url, settings, maxBytes = 6 * 1024 * 1024)
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
            HubSearchCategory.DecisionGguf -> extractDecisionGgufOptions(repo, revision, files)
            HubSearchCategory.SdImage -> extractSdImageOptions(repo, revision, files)
            HubSearchCategory.VideoWan -> extractWanVideoOptions(repo, revision, files)
            HubSearchCategory.MusicAudio -> extractMusicAudioOptions(repo, revision, files)
            HubSearchCategory.Embedding -> extractEmbeddingOptions(repo, revision, files)
        }
    }

    private fun extractEmbeddingOptions(
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
                id = sanitizeModelId("embed", repo, file.path),
                label = fileName,
                repo = repo,
                revision = revision,
                type = HubModelType.Embedding,
                format = HfFileFormat.Gguf,
                entryFile = file.path,
                files = listOf(file),
                totalBytes = file.size,
                quantTag = quant,
                splitWarning = if (isSplit) "分片 GGUF 文件暂不支持单文件直接加载，请选择单文件版本" else null,
                splitWarningKey = if (isSplit) HubSplitWarning.SplitGguf else null
            )
        }.sortedWith(compareBy<HfRepoOption> { it.splitWarning != null }.thenBy { quantPriority(it.quantTag) }.thenBy { it.totalBytes })
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
                splitWarning = if (isSplit) "分片 GGUF 文件暂不支持单文件直接加载，请选择单文件版本" else null,
                splitWarningKey = if (isSplit) HubSplitWarning.SplitGguf else null
            )
        }.sortedWith(compareBy<HfRepoOption> { it.splitWarning != null }.thenBy { quantPriority(it.quantTag) }.thenBy { it.totalBytes })
    }

    private fun extractDecisionGgufOptions(
        repo: String,
        revision: String,
        files: List<HfDownloadFile>
    ): List<HfRepoOption> {
        val ggufs = files.filter { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            lower.endsWith(".gguf") && !lower.contains("mmproj") && !lower.contains("imatrix")
        }
        val repoLower = repo.lowercase(Locale.ROOT)
        return ggufs.map { file ->
            val fileName = file.path.substringAfterLast('/')
            val combined = "$repoLower ${fileName.lowercase(Locale.ROOT)}"
            val isSplit = SPLIT_GGUF_REGEX.containsMatchIn(fileName)
            val isBlockBidir = combined.contains("elephant-1") || combined.contains("elephant_1") ||
                combined.contains("mammoth-1") || combined.contains("mammoth_1")
            val quant = extractQuantTag(fileName)
            HfRepoOption(
                id = sanitizeModelId("decision", repo, file.path),
                label = fileName,
                repo = repo,
                revision = revision,
                type = HubModelType.DecisionGguf,
                format = HfFileFormat.Gguf,
                entryFile = file.path,
                files = listOf(file),
                totalBytes = file.size,
                quantTag = quant,
                splitWarning = if (isSplit) "分片 GGUF 文件暂不支持单文件直接加载，请选择单文件版本" else null,
                companionSummary = if (isBlockBidir) "qev-labels-v2 · 块双向全注意力" else "qev-labels-v2 · 因果注意力（OneJev / System-One）",
                metadata = mapOf(
                    "protocol" to "qev-labels-v2",
                    "attentionMode" to if (isBlockBidir) "block_bidirectional" else "causal"
                ),
                splitWarningKey = if (isSplit) HubSplitWarning.SplitGguf else null,
                companionNoteKey = if (isBlockBidir) HubCompanionNote.DecisionBlockBidir else HubCompanionNote.DecisionCausal
            )
        }.sortedWith(compareBy<HfRepoOption> { it.splitWarning != null }.thenBy { quantPriority(it.quantTag) }.thenBy { it.totalBytes })
    }

    private fun extractMnnChatOptions(
        repo: String,
        revision: String,
        files: List<HfDownloadFile>
    ): List<HfRepoOption> {
        val byPath = files.associateBy { it.path }
        val coreRequired = listOf("config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight")
        if (!coreRequired.all { it in byPath }) return emptyList()
        val tokenizer = byPath["tokenizer.mtok"] ?: byPath["tokenizer.txt"] ?: return emptyList()
        val optional = listOf(
            "visual.mnn", "visual.mnn.weight",
            "audio.mnn", "audio.mnn.weight",
            "embeddings_bf16.bin", "embeddings_fp16.bin", "embeddings_int4.bin", "embeddings_int8.bin"
        )
        val bundle = coreRequired.mapNotNull { byPath[it] } + tokenizer + optional.mapNotNull { byPath[it] }
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
        val repoLower = repo.lowercase(Locale.ROOT)
        val isZImage = repoLower.contains("z-image") || repoLower.contains("z_image")
        val isFlux2Klein4B = repoLower.contains("flux.2-klein-4b") || repoLower.contains("flux-2-klein-4b") ||
            repoLower.contains("flux2-klein-4b") || repoLower.contains("flux.2-klein-base-4b")
        val isFlux2Klein9B = repoLower.contains("flux.2-klein-9b") || repoLower.contains("flux-2-klein-9b") ||
            repoLower.contains("flux2-klein-9b") || repoLower.contains("flux.2-klein-base-9b")
        val isFlux3Klein = repoLower.contains("flux-3-image-klein") || repoLower.contains("flux3-image-klein") ||
            repoLower.contains("flux.3-image-klein")
        val isErnieImage = repoLower.contains("ernie-image") || repoLower.contains("ernie_image")

        val candidates = files.filter { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            val name = lower.substringAfterLast('/')
            val validExt = lower.endsWith(".safetensors") || lower.endsWith(".gguf") || lower.endsWith(".ckpt")
            if (!validExt) return@filter false
            val isComponentSubdir = lower.startsWith("unet/") || lower.startsWith("vae/") ||
                lower.startsWith("text_encoder") || lower.startsWith("safety_checker/") ||
                lower.startsWith("transformer/") || lower.startsWith("tokenizer/")
            val looksAuxiliary = name.contains("vae") || name.contains("lora") || name.contains("clip") ||
                name.contains("t5") || name.contains("mmproj") || name.contains("controlnet") ||
                name.contains("embedding") || name.contains("imatrix")
            !isComponentSubdir && !looksAuxiliary && file.size >= 400_000_000L
        }

        return candidates.map { file ->
            val fileName = file.path.substringAfterLast('/')
            val isSplit = SPLIT_GGUF_REGEX.containsMatchIn(fileName)
            val format = when {
                fileName.endsWith(".gguf", ignoreCase = true) -> HfFileFormat.Gguf
                fileName.endsWith(".ckpt", ignoreCase = true) -> HfFileFormat.Ckpt
                else -> HfFileFormat.Safetensors
            }
            val fileLower = fileName.lowercase(Locale.ROOT)
            val modularZImage = isZImage || fileLower.contains("z-image") || fileLower.contains("z_image")
            val modularFlux2Klein4B = isFlux2Klein4B || fileLower.contains("flux-2-klein-4b") || fileLower.contains("flux2-klein-4b")
            val modularFlux2Klein9B = isFlux2Klein9B || fileLower.contains("flux-2-klein-9b") || fileLower.contains("flux2-klein-9b")
            val modularFlux3Klein = isFlux3Klein || fileLower.contains("flux-3-image-klein") || fileLower.contains("flux3-image-klein")
            val modularErnieImage = isErnieImage || fileLower.contains("ernie-image") || fileLower.contains("ernie_image")

            val (bundleFiles, companionNote, meta) = when {
                modularFlux3Klein -> {
                    val companions = listOf(HubCompanionArtifacts.QWEN3_VL_4B_INSTRUCT, HubCompanionArtifacts.FLUX2_AE_VAE)
                    val isBase = repoLower.contains("base") || fileLower.contains("base")
                    Triple(
                        listOf(file) + companions,
                        "自动附带共享 Qwen3-VL-4B 文本编码器 + FLUX.2 VAE（支持文生图与参考图编辑）",
                        mapOf(
                            "arch" to "flux3_klein",
                            "llmCompanion" to HubCompanionArtifacts.QWEN3_VL_4B_INSTRUCT.sharedCompanionKey.orEmpty(),
                            "vaeCompanion" to HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty(),
                            "supportsEdit" to "true",
                            "defaultSteps" to if (isBase) "20" else "16",
                            "defaultCfg" to if (isBase) "3.5" else "1.0"
                        )
                    )
                }
                modularErnieImage -> {
                    val companions = listOf(HubCompanionArtifacts.MINISTRAL_3_3B_INSTRUCT, HubCompanionArtifacts.FLUX2_AE_VAE)
                    val isTurbo = repoLower.contains("turbo") || fileLower.contains("turbo")
                    Triple(
                        listOf(file) + companions,
                        "自动附带共享 Ministral-3-3B 文本编码器 + FLUX.2 VAE（已下载则自动复用）",
                        mapOf(
                            "arch" to "ernie_image",
                            "llmCompanion" to HubCompanionArtifacts.MINISTRAL_3_3B_INSTRUCT.sharedCompanionKey.orEmpty(),
                            "vaeCompanion" to HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty(),
                            "defaultSteps" to if (isTurbo) "8" else "20",
                            "defaultCfg" to if (isTurbo) "1.0" else "5.0"
                        )
                    )
                }
                modularZImage -> {
                    val companions = listOf(HubCompanionArtifacts.QWEN3_4B_INSTRUCT_2507, HubCompanionArtifacts.FLUX1_AE_VAE)
                    Triple(
                        listOf(file) + companions,
                        "自动附带共享 Qwen3-4B 文本编码器 + FLUX VAE（已下载则自动复用）",
                        mapOf(
                            "arch" to "z_image",
                            "llmCompanion" to HubCompanionArtifacts.QWEN3_4B_INSTRUCT_2507.sharedCompanionKey.orEmpty(),
                            "vaeCompanion" to HubCompanionArtifacts.FLUX1_AE_VAE.sharedCompanionKey.orEmpty(),
                            "defaultSteps" to if (repoLower.contains("turbo") || fileLower.contains("turbo")) "8" else "20",
                            "defaultCfg" to if (repoLower.contains("turbo") || fileLower.contains("turbo")) "1.0" else "4.5"
                        )
                    )
                }
                modularFlux2Klein9B -> {
                    val companions = listOf(HubCompanionArtifacts.QWEN3_8B_BASE_TE, HubCompanionArtifacts.FLUX2_AE_VAE)
                    val isBase = repoLower.contains("base") || fileLower.contains("base")
                    Triple(
                        listOf(file) + companions,
                        "自动附带共享 Qwen3-8B 文本编码器 + FLUX.2 VAE（支持文生图与参考图编辑）",
                        mapOf(
                            "arch" to "flux2_klein_9b",
                            "llmCompanion" to HubCompanionArtifacts.QWEN3_8B_BASE_TE.sharedCompanionKey.orEmpty(),
                            "vaeCompanion" to HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty(),
                            "supportsEdit" to "true",
                            "defaultSteps" to if (isBase) "20" else "4",
                            "defaultCfg" to if (isBase) "4.0" else "1.0"
                        )
                    )
                }
                modularFlux2Klein4B -> {
                    val companions = listOf(HubCompanionArtifacts.QWEN3_4B_BASE_TE, HubCompanionArtifacts.FLUX2_AE_VAE)
                    val isBase = repoLower.contains("base") || fileLower.contains("base")
                    Triple(
                        listOf(file) + companions,
                        "自动附带共享 Qwen3-4B 文本编码器 + FLUX.2 VAE（支持文生图与参考图编辑）",
                        mapOf(
                            "arch" to "flux2_klein",
                            "llmCompanion" to HubCompanionArtifacts.QWEN3_4B_BASE_TE.sharedCompanionKey.orEmpty(),
                            "vaeCompanion" to HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty(),
                            "supportsEdit" to "true",
                            "defaultSteps" to if (isBase) "20" else "4",
                            "defaultCfg" to if (isBase) "4.0" else "1.0"
                        )
                    )
                }
                else -> Triple(listOf(file), null, mapOf("arch" to "sd_single"))
            }

            HfRepoOption(
                id = sanitizeModelId("sd", repo, file.path),
                label = fileName,
                repo = repo,
                revision = revision,
                type = HubModelType.SdImage,
                format = format,
                entryFile = file.path,
                files = bundleFiles,
                totalBytes = bundleFiles.sumOf { it.size },
                quantTag = extractQuantTag(fileName) ?: if (fileName.contains("fp16", ignoreCase = true)) "FP16" else null,
                splitWarning = if (isSplit) "分片 GGUF 文件暂不支持单文件加载" else null,
                companionSummary = companionNote,
                metadata = meta,
                splitWarningKey = if (isSplit) HubSplitWarning.SplitGguf else null,
                companionNoteKey = when {
                    modularFlux3Klein -> HubCompanionNote.Flux3KleinShared
                    modularErnieImage -> HubCompanionNote.ErnieImageShared
                    modularZImage -> HubCompanionNote.ZImageShared
                    modularFlux2Klein9B -> HubCompanionNote.Flux2Klein9BShared
                    modularFlux2Klein4B -> HubCompanionNote.Flux2Shared
                    else -> null
                }
            )
        }.sortedWith(compareBy<HfRepoOption> { it.splitWarning != null }.thenBy { quantPriority(it.quantTag) }.thenBy { it.totalBytes })
    }

    private fun extractWanVideoOptions(
        repo: String,
        revision: String,
        files: List<HfDownloadFile>
    ): List<HfRepoOption> {
        val repoLower = repo.lowercase(Locale.ROOT)
        val candidates = files.filter { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            val name = lower.substringAfterLast('/')
            lower.endsWith(".gguf") &&
                !name.contains("umt5") && !name.contains("t5xxl") && !name.contains("clip") &&
                !name.contains("vae") && !name.contains("tae") && !name.contains("imatrix") &&
                !name.contains("high_noise") && !name.contains("high-noise") &&
                !name.contains("qwen3") && !name.contains("minimax-h3-image") && !name.contains("minimax_h3_image") &&
                file.size >= 400_000_000L
        }
        return candidates.map { file ->
            val fileName = file.path.substringAfterLast('/')
            val fileLower = fileName.lowercase(Locale.ROOT)
            val lower = "$repoLower $fileLower"
            val isSplit = SPLIT_GGUF_REGEX.containsMatchIn(fileName)
            val isMiniMaxH3 = repoLower.contains("minimax-h3") || repoLower.contains("minimax_h3") ||
                fileLower.contains("minimax-h3") || fileLower.contains("minimax_h3")
            val quant = extractQuantTag(fileName)

            if (isMiniMaxH3) {
                val isUnpruned = !fileLower.contains("pruned") && file.size > 13_000_000_000L
                val companions = listOf(
                    HubCompanionArtifacts.MINIMAX_H3_TE_Q2_K,
                    HubCompanionArtifacts.MINIMAX_H3_VIDEO_VAE,
                    HubCompanionArtifacts.MINIMAX_H3_AUDIO_VAE,
                    HubCompanionArtifacts.PI_H3_2STEP_ADAPTER,
                    HubCompanionArtifacts.PI_H3_4STEP_ADAPTER
                )
                val allFiles = listOf(file) + companions
                return@map HfRepoOption(
                    id = sanitizeModelId("minimax-h3", repo, file.path),
                    label = fileName,
                    repo = repo,
                    revision = revision,
                    type = HubModelType.VideoWan,
                    format = HfFileFormat.Gguf,
                    entryFile = file.path,
                    files = allFiles,
                    totalBytes = allFiles.sumOf { it.size },
                    quantTag = quant,
                    splitWarning = when {
                        isSplit -> "分片 GGUF 文件暂不支持单文件直接加载"
                        isUnpruned -> "未剪枝 MiniMax-H3 31B 体积过大，手机端请选择 Pruned（17B）剪枝版 Q2_K / Q3_K"
                        else -> null
                    },
                    companionSummary = "自动附带共享 Qwen3-VL 文本编码器、MiniMax-H3 音视频 VAE 及 pi-H3 2/4 步适配器（磁盘分段流式运行）",
                    metadata = mapOf(
                        "arch" to "minimax_h3",
                        "h3Mode" to if (fileLower.contains("ref2va")) "ref2va" else "fl2va",
                        "llmCompanion" to HubCompanionArtifacts.MINIMAX_H3_TE_Q2_K.sharedCompanionKey.orEmpty(),
                        "vaeCompanion" to HubCompanionArtifacts.MINIMAX_H3_VIDEO_VAE.sharedCompanionKey.orEmpty(),
                        "audioVaeCompanion" to HubCompanionArtifacts.MINIMAX_H3_AUDIO_VAE.sharedCompanionKey.orEmpty(),
                        "piH3Adapter2Step" to HubCompanionArtifacts.PI_H3_2STEP_ADAPTER.sharedCompanionKey.orEmpty(),
                        "piH3Adapter4Step" to HubCompanionArtifacts.PI_H3_4STEP_ADAPTER.sharedCompanionKey.orEmpty(),
                        "defaultSteps" to "2",
                        "defaultCfg" to "1.0",
                        "defaultShift" to "12.0",
                        "defaultFps" to "24",
                        "defaultFrames" to "22",
                        "defaultWidth" to "480",
                        "defaultHeight" to "288"
                    ),
                    splitWarningKey = when {
                        isSplit -> HubSplitWarning.SplitGguf
                        isUnpruned -> HubSplitWarning.MiniMaxH3Unpruned
                        else -> null
                    },
                    companionNoteKey = HubCompanionNote.MiniMaxH3PiShared,
                    runtimeRamBytesOverride = 6_200_000_000L,
                    recommendedRamGbOverride = 12
                )
            }

            val isWan22Ti2v5B = lower.contains("ti2v") || (lower.contains("wan2.2") && lower.contains("5b")) ||
                (lower.contains("wan2_2") && lower.contains("5b")) || (lower.contains("wan-2.2") && lower.contains("5b"))
            val isTurbo = lower.contains("turbo") || lower.contains("lightx2v") || lower.contains("distill") || lower.contains("selfforcing")
            val isMoE14B = lower.contains("a14b") || lower.contains("lownoise") || lower.contains("low_noise")
            val tae = if (isWan22Ti2v5B) HubCompanionArtifacts.TAE_WAN2_2 else HubCompanionArtifacts.TAE_WAN2_1
            val companions = listOf(HubCompanionArtifacts.UMT5_XXL_Q3_K_S, tae)
            val allFiles = listOf(file) + companions
            HfRepoOption(
                id = sanitizeModelId("wan", repo, file.path),
                label = fileName,
                repo = repo,
                revision = revision,
                type = HubModelType.VideoWan,
                format = HfFileFormat.Gguf,
                entryFile = file.path,
                files = allFiles,
                totalBytes = allFiles.sumOf { it.size },
                quantTag = quant,
                splitWarning = when {
                    isSplit -> "分片 GGUF 文件暂不支持单文件直接加载"
                    isMoE14B -> "Wan2.2 A14B 需要高低噪双模型且内存占用 >16 GB，手机端建议选择 Wan2.2-TI2V-5B 或 Wan2.1-1.3B"
                    else -> null
                },
                companionSummary = "自动复用或附带共享 UMT5-XXL 文本编码器 + ${tae.sharedCompanionKey}",
                metadata = mapOf(
                    "arch" to if (isWan22Ti2v5B) "wan22_ti2v" else "wan21_t2v",
                    "t5Companion" to HubCompanionArtifacts.UMT5_XXL_Q3_K_S.sharedCompanionKey.orEmpty(),
                    "taeCompanion" to tae.sharedCompanionKey.orEmpty(),
                    "defaultSteps" to if (isTurbo) "4" else "10",
                    "defaultCfg" to if (isTurbo) "1.0" else "5.0",
                    "defaultShift" to if (isWan22Ti2v5B) "5.0" else "3.0"
                ),
                splitWarningKey = when {
                    isSplit -> HubSplitWarning.SplitGguf
                    isMoE14B -> HubSplitWarning.WanMoE14B
                    else -> null
                },
                companionNoteKey = HubCompanionNote.WanShared,
                companionNoteArg = tae.sharedCompanionKey
            )
        }.sortedWith(
            compareBy<HfRepoOption> { it.splitWarning != null }
                .thenBy { if (it.metadata["arch"] == "minimax_h3" && it.metadata["h3Mode"] == "ref2va") 1 else 0 }
                .thenBy {
                    if (it.metadata["arch"] == "minimax_h3") miniMaxH3QuantPriority(it.quantTag)
                    else quantPriority(it.quantTag)
                }
                .thenBy { it.totalBytes }
        )
    }

    private fun extractMusicAudioOptions(
        repo: String,
        revision: String,
        files: List<HfDownloadFile>
    ): List<HfRepoOption> {
        val byPath = files.associateBy { it.path }
        val results = mutableListOf<HfRepoOption>()

        // 1) YuE2 GGUF packages (requires vae + sidecars from the same repo).
        val yueSidecars = listOf(
            "sidecars/yue2-generation-config.json",
            "sidecars/yue2-model-config.json",
            "sidecars/yue2-qwen.tiktoken",
            "sidecars/yue2-vae-config.json"
        )
        val yueVae = byPath["yue2-vae-f16.gguf"] ?: byPath["yue2-vae-f32.gguf"]
        if (yueVae != null && yueSidecars.all { it in byPath }) {
            val sidecarFiles = yueSidecars.mapNotNull { byPath[it] }
            val yueModels = files.filter { file ->
                val lower = file.path.lowercase(Locale.ROOT)
                lower.endsWith(".gguf") && lower.contains("yue2") && !lower.contains("vae") && file.size >= 500_000_000L
            }
            yueModels.forEach { mainFile ->
                val fileName = mainFile.path.substringAfterLast('/')
                val quant = extractQuantTag(fileName)
                val bundle = sidecarFiles + yueVae + mainFile
                results += HfRepoOption(
                    id = sanitizeModelId("music", repo, mainFile.path),
                    label = "YuE2-3B · ${quant ?: fileName}",
                    repo = repo,
                    revision = revision,
                    type = HubModelType.MusicAudio,
                    format = HfFileFormat.Gguf,
                    entryFile = mainFile.path,
                    files = bundle,
                    totalBytes = bundle.sumOf { it.size },
                    quantTag = quant,
                    companionSummary = "含 ${yueVae.path} 与 YuE2 词表/配置 sidecar",
                    metadata = mapOf(
                        "family" to "yue2",
                        "modelGguf" to mainFile.path,
                        "vaeGguf" to yueVae.path
                    ),
                    companionNoteKey = HubCompanionNote.Yue2Sidecars,
                    companionNoteArg = yueVae.path
                )
            }
        }

        // 2) Self-contained single-file audio.cpp music GGUFs (ACE-Step 1.5, HeartMuLa, Stable Audio 3).
        files.filter { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            lower.endsWith(".gguf") && file.size >= 300_000_000L
        }.forEach { file ->
            val lower = file.path.lowercase(Locale.ROOT)
            val fileName = file.path.substringAfterLast('/')
            val familyInfo: Triple<String, String, Map<String, String>>? = when {
                lower.contains("ace-step") || lower.contains("acestep") -> {
                    val isTurbo = lower.contains("turbo")
                    val variant = when {
                        lower.contains("xl-turbo") -> "XL Turbo"
                        lower.contains("xl-sft") -> "XL SFT"
                        isTurbo -> "1.5 Turbo"
                        else -> "1.5 Base"
                    }
                    val ditPath = when {
                        lower.contains("xl-turbo") -> "acestep-v15-xl-turbo"
                        lower.contains("xl-sft") -> "acestep-v15-xl-sft"
                        isTurbo -> "acestep-v15-turbo"
                        else -> "acestep-v15-base"
                    }
                    Triple(
                        "ace_step",
                        "ACE-Step $variant · ${extractQuantTag(fileName) ?: "GGUF"}",
                        mapOf(
                            "family" to "ace_step",
                            "ditVariant" to ditPath,
                            "defaultSteps" to if (isTurbo) "8" else "24"
                        )
                    )
                }
                lower.contains("heartmula") -> Triple(
                    "heartmula",
                    "HeartMuLa 3B · ${extractQuantTag(fileName) ?: "GGUF"}",
                    mapOf("family" to "heartmula", "defaultSteps" to "10")
                )
                lower.contains("stable-audio") || lower.contains("stable_audio") -> {
                    val flavor = when {
                        lower.contains("sfx") -> "3 Small SFX"
                        lower.contains("medium") -> "3 Medium"
                        else -> "3 Small Music"
                    }
                    Triple(
                        "stable_audio",
                        "Stable Audio $flavor · ${extractQuantTag(fileName) ?: "GGUF"}",
                        mapOf("family" to "stable_audio", "defaultSteps" to "8")
                    )
                }
                else -> null
            }
            if (familyInfo != null) {
                val (_, label, meta) = familyInfo
                results += HfRepoOption(
                    id = sanitizeModelId("music", repo, file.path),
                    label = label,
                    repo = repo,
                    revision = revision,
                    type = HubModelType.MusicAudio,
                    format = HfFileFormat.Gguf,
                    entryFile = file.path,
                    files = listOf(file),
                    totalBytes = file.size,
                    quantTag = extractQuantTag(fileName),
                    companionSummary = "单文件自包含 GGUF（已内嵌配置与词表）",
                    metadata = meta,
                    companionNoteKey = HubCompanionNote.SelfContainedAudioGguf
                )
            }
        }

        return results.sortedWith(compareBy<HfRepoOption> { quantPriority(it.quantTag) }.thenBy { it.totalBytes })
    }

    fun quickSearchKeywords(category: HubSearchCategory): List<String> = when (category) {
        HubSearchCategory.GgufChat -> listOf("Qwen3.5", "Gemma 4", "Qwen3", "DeepSeek-R1", "Llama-3.2", "Phi-4")
        HubSearchCategory.MnnChat -> listOf("Qwen3.5", "Qwen3", "DeepSeek", "Qwen2.5", "Gemma")
        HubSearchCategory.DecisionGguf -> listOf("OneJev1.6", "elephant-1", "System-One")
        HubSearchCategory.SdImage -> listOf("FLUX.3", "ERNIE-Image", "Z-Image", "FLUX.2-klein", "SDXL-Turbo")
        HubSearchCategory.VideoWan -> listOf("MiniMax-H3", "Wan2.2", "Wan2.1")
        HubSearchCategory.MusicAudio -> listOf("ACE-Step", "Yue2", "HeartMuLa", "Stable-Audio")
        HubSearchCategory.Embedding -> listOf("Qwen3-Embedding", "bge-small-zh", "bge-m3", "nomic-embed")
    }

    fun curatedRecommendations(category: HubSearchCategory): List<HfRepoSummary> = when (category) {
        HubSearchCategory.GgufChat -> listOf(
            HfRepoSummary("unsloth/Qwen3.5-4B-GGUF", "unsloth", "Qwen3.5-4B-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen3.5"), false, "Qwen3.5 4B 混合线性注意力架构，长上下文内存增长平缓", "curated_qwen35_4b"),
            HfRepoSummary("unsloth/gemma-4-E4B-it-GGUF", "unsloth", "gemma-4-E4B-it-GGUF", 0, 0, "text-generation", listOf("gguf", "gemma4"), false, "Google Gemma 4 E4B 新一代端侧主力（4B 有效参数，多语种强）", "curated_gemma4_e4b"),
            HfRepoSummary("unsloth/gemma-4-E2B-it-GGUF", "unsloth", "gemma-4-E2B-it-GGUF", 0, 0, "text-generation", listOf("gguf", "gemma4"), false, "Google Gemma 4 E2B 轻量端侧模型，低内存手机运行快", "curated_gemma4_e2b"),
            HfRepoSummary("unsloth/Qwen3.5-9B-GGUF", "unsloth", "Qwen3.5-9B-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen3.5"), false, "Qwen3.5 9B 旗舰端侧推理与深度思考（建议 12 GB+ 内存）", "curated_qwen35_9b"),
            HfRepoSummary("unsloth/Qwen3-0.6B-GGUF", "unsloth", "Qwen3-0.6B-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen3"), false, "超轻量体验，支持深度思考，约 400 MB", "curated_qwen3_06b"),
            HfRepoSummary("unsloth/Qwen3-1.7B-GGUF", "unsloth", "Qwen3-1.7B-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen3"), false, "支持深度思考模式的新一代小模型，含 Q4_K_M", "curated_qwen3_17b"),
            HfRepoSummary("unsloth/Qwen3-4B-GGUF", "unsloth", "Qwen3-4B-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen3"), false, "4B 级别综合推理与中文能力领先，建议 8 GB+ 内存", "curated_qwen3_4b"),
            HfRepoSummary("Qwen/Qwen2.5-3B-Instruct-GGUF", "Qwen", "Qwen2.5-3B-Instruct-GGUF", 0, 0, "text-generation", listOf("gguf", "qwen2"), false, "均衡稳定，中英问答与总结速度快", "curated_qwen25_3b"),
            HfRepoSummary("unsloth/gemma-3-4b-it-GGUF", "unsloth", "gemma-3-4b-it-GGUF", 0, 0, "text-generation", listOf("gguf", "gemma3"), false, "Google Gemma 3 4B 多语言端侧模型", "curated_gemma3_4b"),
            HfRepoSummary("bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", "bartowski", "DeepSeek-R1-Distill-Qwen-1.5B-GGUF", 0, 0, "text-generation", listOf("gguf", "deepseek"), false, "DeepSeek R1 1.5B 数学与逻辑推理蒸馏模型", "curated_dsr1_15b"),
            HfRepoSummary("bartowski/Llama-3.2-3B-Instruct-GGUF", "bartowski", "Llama-3.2-3B-Instruct-GGUF", 0, 0, "text-generation", listOf("gguf", "llama"), false, "Meta 轻量端侧英文/通用对话模型", "curated_llama32_3b")
        )
        HubSearchCategory.MnnChat -> listOf(
            HfRepoSummary("taobao-mnn/Qwen3.5-4B-MNN", "taobao-mnn", "Qwen3.5-4B-MNN", 0, 0, "text-generation", listOf("mnn", "qwen3.5"), false, "MNN 官方转换 Qwen3.5 4B（OpenCL 加速，长上下文友好）", "curated_mnn_qwen35_4b"),
            HfRepoSummary("taobao-mnn/Qwen3.5-2B-MNN", "taobao-mnn", "Qwen3.5-2B-MNN", 0, 0, "text-generation", listOf("mnn", "qwen3.5"), false, "MNN 官方转换 Qwen3.5 2B（日常流畅均衡）", "curated_mnn_qwen35_2b"),
            HfRepoSummary("taobao-mnn/Qwen3.5-0.8B-MNN", "taobao-mnn", "Qwen3.5-0.8B-MNN", 0, 0, "text-generation", listOf("mnn", "qwen3.5"), false, "MNN 官方转换 Qwen3.5 0.8B（秒级加载）", "curated_mnn_qwen35_08b"),
            HfRepoSummary("taobao-mnn/Qwen3-1.7B-MNN", "taobao-mnn", "Qwen3-1.7B-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 Qwen3 1.7B（支持 OpenCL）", "curated_mnn_qwen3_17b"),
            HfRepoSummary("taobao-mnn/Qwen3-4B-MNN", "taobao-mnn", "Qwen3-4B-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 Qwen3 4B", "curated_mnn_qwen3_4b"),
            HfRepoSummary("taobao-mnn/DeepSeek-R1-1.5B-Qwen-MNN", "taobao-mnn", "DeepSeek-R1-1.5B-Qwen-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 DeepSeek-R1 1.5B", "curated_mnn_dsr1_15b"),
            HfRepoSummary("taobao-mnn/Qwen2.5-3B-Instruct-MNN", "taobao-mnn", "Qwen2.5-3B-Instruct-MNN", 0, 0, "text-generation", listOf("mnn"), false, "MNN 官方转换 Qwen2.5 3B", "curated_mnn_qwen25_3b")
        )
        HubSearchCategory.DecisionGguf -> listOf(
            HfRepoSummary("mradermacher/OneJev1.6-0.8B-GGUF", "mradermacher", "OneJev1.6-0.8B-GGUF", 0, 0, "text-classification", listOf("gguf", "onejev", "decision-model"), false, "OneJev 1.6 0.8B 超轻量决策模型（Apache-2.0，Q4 ~500 MB）", "curated_onejev_08b"),
            HfRepoSummary("mradermacher/OneJev1.6-4B-GGUF", "mradermacher", "OneJev1.6-4B-GGUF", 0, 0, "text-classification", listOf("gguf", "onejev", "decision-model"), false, "OneJev 1.6 4B（Decision Index 62.0，Apache-2.0，Q4 ~2.5 GB）", "curated_onejev_4b"),
            HfRepoSummary("mradermacher/OneJev1.6-9B-GGUF", "mradermacher", "OneJev1.6-9B-GGUF", 0, 0, "text-classification", listOf("gguf", "onejev", "decision-model"), false, "OneJev 1.6 9B 旗舰端侧决策模型（Apache-2.0）", "curated_onejev_9b"),
            HfRepoSummary("mradermacher/elephant-1-GGUF", "mradermacher", "elephant-1-GGUF", 0, 0, "text-classification", listOf("gguf", "elephant-1", "decision-model"), false, "Base Labs Elephant-1 9B 全系列量化（AA Decision Index 66.4）", "curated_elephant_1"),
            HfRepoSummary("waseigo/elephant-1-decision-GGUF", "waseigo", "elephant-1-decision-GGUF", 0, 0, "text-classification", listOf("gguf", "elephant-1", "decision-model"), false, "Elephant-1 9B 社区 GGUF 镜像版本", "curated_elephant_mirror")
        )
        HubSearchCategory.SdImage -> listOf(
            HfRepoSummary("unsloth/flux-3-image-klein-GGUF", "unsloth", "flux-3-image-klein-GGUF", 0, 0, "text-to-image", listOf("flux3", "gguf"), false, "BFL 新一代 FLUX.3 Image Klein，支持文生图与参考图编辑（AA ELO ~1039）", "curated_flux3_klein"),
            HfRepoSummary("unsloth/ERNIE-Image-Turbo-GGUF", "unsloth", "ERNIE-Image-Turbo-GGUF", 0, 0, "text-to-image", listOf("ernie-image", "gguf"), false, "百度 ERNIE-Image Turbo，8 步高质量出图与中英文文字排版", "curated_ernie_image"),
            HfRepoSummary("unsloth/Z-Image-Turbo-GGUF", "unsloth", "Z-Image-Turbo-GGUF", 0, 0, "text-to-image", listOf("z-image", "gguf"), false, "通义 Z-Image 6B Turbo，8 步高质量出图与中英文文字渲染", "curated_zimage"),
            HfRepoSummary("unsloth/FLUX.2-klein-4B-GGUF", "unsloth", "FLUX.2-klein-4B-GGUF", 0, 0, "text-to-image", listOf("flux2", "gguf"), false, "Black Forest Labs 4B 4 步极速出图与参考图编辑（Q4 仅 2.5 GB）", "curated_flux2"),
            HfRepoSummary("unsloth/FLUX.2-klein-9B-GGUF", "unsloth", "FLUX.2-klein-9B-GGUF", 0, 0, "text-to-image", listOf("flux2", "gguf"), false, "BFL FLUX.2 Klein 9B 高画质 4 步生图与编辑（12 GB+ 手机推荐）", "curated_flux2_9b"),
            HfRepoSummary("stabilityai/sdxl-turbo", "stabilityai", "sdxl-turbo", 0, 0, "text-to-image", listOf("sdxl", "turbo"), false, "1–4 步快速出图，单文件 safetensors", "curated_sdxl_turbo"),
            HfRepoSummary("stabilityai/sd-turbo", "stabilityai", "sd-turbo", 0, 0, "text-to-image", listOf("sd2", "turbo"), false, "轻量快速文生图，内存占用低", "curated_sd_turbo"),
            HfRepoSummary("Lykon/DreamShaper", "Lykon", "DreamShaper", 0, 0, "text-to-image", listOf("sd15"), false, "经典 SD 1.5 风格化通用生图模型", "curated_dreamshaper"),
            HfRepoSummary("stable-diffusion-v1-5/stable-diffusion-v1-5", "stable-diffusion-v1-5", "stable-diffusion-v1-5", 0, 0, "text-to-image", listOf("sd15"), false, "SD 1.5 基础单文件权重", "curated_sd15")
        )
        HubSearchCategory.VideoWan -> listOf(
            HfRepoSummary("unsloth/MiniMax-H3-GGUF", "unsloth", "MiniMax-H3-GGUF", 0, 0, "text-to-video", listOf("minimax-h3", "pi-h3", "gguf"), false, "MiniMax-H3 剪枝版音画同步视频（配 pi-H3 2/4 步加速与磁盘流式加载）", "curated_minimax_h3_unsloth"),
            HfRepoSummary("Abiray/MiniMax-H3-Pruned-GGUF", "Abiray", "MiniMax-H3-Pruned-GGUF", 0, 0, "text-to-video", listOf("minimax-h3", "pi-h3", "gguf"), false, "MiniMax-H3 17B 剪枝版多量化合集（含 Q3_K_M / Q4_K_M）", "curated_minimax_h3_abiray"),
            HfRepoSummary("hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF", "hum-ma", "Wan2.2-TI2V-5B-Turbo-GGUF", 0, 0, "text-to-video", listOf("wan2.2", "gguf"), false, "4 步加速版 Wan2.2 5B，支持文生/图生视频", "curated_wan22_turbo"),
            HfRepoSummary("QuantStack/Wan2.2-TI2V-5B-GGUF", "QuantStack", "Wan2.2-TI2V-5B-GGUF", 0, 0, "text-to-video", listOf("wan2.2", "gguf"), false, "官方 Wan2.2 5B 全系列量化（Q3–Q8 可选）", "curated_wan22_qs"),
            HfRepoSummary("unsloth/Wan2.2-TI2V-5B-GGUF", "unsloth", "Wan2.2-TI2V-5B-GGUF", 0, 0, "text-to-video", listOf("wan2.2", "gguf"), false, "Unsloth 动态量化 Wan2.2-TI2V-5B", "curated_wan22_unsloth"),
            HfRepoSummary("samuelchristlie/Wan2.1-T2V-1.3B-GGUF", "samuelchristlie", "Wan2.1-T2V-1.3B-GGUF", 0, 0, "text-to-video", listOf("wan2.1", "gguf"), false, "轻量 1.3B 视频模型，低内存手机运行更快", "curated_wan21_13b"),
            HfRepoSummary("off-grid-ai/Wan2.1-T2V-1.3B-GGUF", "off-grid-ai", "Wan2.1-T2V-1.3B-GGUF", 0, 0, "text-to-video", listOf("wan2.1", "gguf"), false, "Wan2.1 1.3B 端侧多量化 GGUF 合集", "curated_wan21_offgrid")
        )
        HubSearchCategory.MusicAudio -> listOf(
            HfRepoSummary("audio-cpp/audio.cpp-gguf", "audio-cpp", "audio.cpp-gguf", 0, 0, "text-to-audio", listOf("ace-step", "heartmula", "stable-audio", "gguf"), false, "含 ACE-Step 1.5 Turbo、HeartMuLa 3B、Stable Audio 3 Small/Medium 单文件 GGUF", "curated_audiocpp"),
            HfRepoSummary("audio-cpp/Yue2-3B-GGUF", "audio-cpp", "Yue2-3B-GGUF", 0, 0, "text-to-audio", listOf("yue2", "gguf"), false, "YuE2-3B 多精度版本（含 2.3 GB 轻量版与 Q8_0 高质量版）", "curated_yue2")
        )
        HubSearchCategory.Embedding -> listOf(
            HfRepoSummary("CompendiumLabs/bge-small-zh-v1.5-gguf", "CompendiumLabs", "bge-small-zh-v1.5-gguf", 0, 0, "feature-extraction", listOf("gguf", "bge", "zh"), false, "轻量中文/双语知识库嵌入模型（Q8_0 仅 26 MB，毫秒级向量化）", "curated_bge_small_zh"),
            HfRepoSummary("Qwen/Qwen3-Embedding-0.6B-GGUF", "Qwen", "Qwen3-Embedding-0.6B-GGUF", 0, 0, "feature-extraction", listOf("gguf", "qwen3-embedding"), false, "Qwen3-Embedding 0.6B 高精度多语言向量嵌入模型（支持长文档）", "curated_qwen3_emb_06b")
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

    private fun miniMaxH3QuantPriority(tag: String?): Int = when (tag?.removePrefix("UD-")) {
        "Q2_K" -> 0
        "Q2_K_XL" -> 1
        "Q3_K_M", "Q3_K_XL" -> 2
        "Q3_K_S", "Q3_K" -> 3
        "Q4_K_M", "Q4_K_XL" -> 4
        "Q4_K_S", "Q4_0" -> 5
        "Q5_K_M", "Q5_K_XL" -> 6
        "Q6_K", "Q6_K_XL" -> 7
        "Q8_0", "Q8_K_XL" -> 8
        else -> 9
    }

    private fun quantPriority(tag: String?): Int = when (tag?.removePrefix("UD-")) {
        "Q4_K_M" -> 0
        "Q4_K_XL" -> 1
        "Q4_K_S" -> 2
        "Q4_0" -> 3
        "IQ4_XS" -> 4
        "Q5_K_M" -> 5
        "Q5_K_XL" -> 6
        "Q6_K" -> 7
        "Q6_K_XL" -> 8
        "Q8_0", "Q8_K_XL" -> 9
        "Q3_K_XL" -> 10
        "Q3_K_M" -> 11
        "Q3_K" -> 12
        "Q2_K_XL" -> 13
        "Q2_K" -> 14
        else -> 15
    }

    private val QUANT_REGEX = Regex("""(?i)\b((?:UD-)?(?:IQ[1-4]_[A-Z0-9]+|Q[2-8]_[01K](?:_(?:XL|[SML]))?)|F16|BF16|FP16|FP32)\b""")
    private val SPLIT_GGUF_REGEX = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)
}
