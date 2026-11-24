package com.tiggy.qwenimage.data

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.Locale

/** Metadata extracted from the binary header of a GGUF v2/v3 file without loading tensor weights. */
data class GgufHeaderInfo(
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val architecture: String? = null,
    val modelName: String? = null,
    val fileType: Int? = null,
    val fileTypeQuantTag: String? = null
)

enum class LocalImportConfidence { High, Medium, Low }

/** Automatic classification of a locally picked model file before copying into App storage. */
data class LocalModelClassification(
    val suggestedType: HubModelType,
    val confidence: LocalImportConfidence,
    val displayName: String,
    val quantTag: String?,
    val archHint: String?,
    val metadata: Map<String, String> = emptyMap(),
    val companionSharedKey: String? = null,
    val expectedCompanionKeys: List<String> = emptyList()
)

/**
 * Streaming GGUF v2/v3 header parser + modality classifier.
 * Only reads small metadata entries and skips large arrays (such as tokenizer vocabularies) in-place,
 * so multi-GB model files can be inspected in a few milliseconds with negligible RAM.
 */
object GgufHeaderParser {
    private const val GGUF_MAGIC_LE = 0x46554747 // "GGUF" little-endian
    private const val MAX_KV_ENTRIES = 4096
    private const val MAX_STRING_BYTES = 2 * 1024 * 1024 // 2 MB sanity cap per string
    private const val MAX_ARRAY_ELEMENTS = 4_000_000L

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    private val QUANT_REGEX = Regex(
        """(?i)(?:^|[._-])((?:UD-)?(?:IQ[1-4]_(?:XXS|XS|S|M|NL)|Q[2-8]_(?:0|1|K(?:_(?:XXS|XS|S|M|L|XL))?)|BF16|F16|F32|FP16|FP8|MXFP4))(?:[._-]|$)"""
    )

    private val LLM_ARCHS = setOf(
        "llama", "qwen", "qwen2", "qwen2moe", "qwen3", "qwen3moe", "qwen35", "qwen35moe",
        "qwen3next", "gemma", "gemma2", "gemma3", "gemma3n", "gemma4",
        "mistral", "mixtral", "mistral3", "ministral", "phi2", "phi3", "phi4",
        "deepseek", "deepseek2", "internlm2", "chatglm", "glm4", "baichuan",
        "starcoder", "starcoder2", "command-r", "cohere2", "smollm3", "lfm2",
        "granite", "gpt2", "olmo", "olmo2", "falcon", "mpt", "rwkv", "rwkv6",
        "mamba", "mamba2", "jamba", "nemotron", "exaone", "hunyuan-dense",
        "hunyuan-moe", "seed_oss", "apertus", "bailingmoe", "minicpm3", "bert", "nomic-bert"
    )

    private val VIDEO_ARCHS = setOf("wan", "wan2", "minimax_h3", "ltxv", "hunyuan_video", "cogvideox")
    private val IMAGE_ARCHS = setOf("flux", "flux2", "flux3", "sd", "sdxl", "sd3", "z_image", "ernie_image", "chroma", "qwen_image")

    private val WANTED_KEYS = setOf("general.architecture", "general.name", "general.file_type")

    fun parse(input: InputStream): GgufHeaderInfo? {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 32 * 1024)
        val dis = DataInputStream(buffered)
        return try {
            val magic = readIntLE(dis)
            if (magic != GGUF_MAGIC_LE) return null
            val version = readIntLE(dis)
            if (version !in 2..3) return null
            val tensorCount = readLongLE(dis)
            val kvCount = readLongLE(dis)
            if (tensorCount < 0L || kvCount < 0L || kvCount > MAX_KV_ENTRIES) {
                return GgufHeaderInfo(version = version, tensorCount = tensorCount, kvCount = kvCount)
            }

            var arch: String? = null
            var name: String? = null
            var fileType: Int? = null
            var remainingWanted = WANTED_KEYS.size

            for (i in 0L until kvCount) {
                val key = readKeyOrSkip(dis)
                val valueType = readIntLE(dis)
                if (key != null && key in WANTED_KEYS) {
                    when (key) {
                        "general.architecture" -> if (valueType == TYPE_STRING) arch = readStringLE(dis) else skipValue(dis, valueType)
                        "general.name" -> if (valueType == TYPE_STRING) name = readStringLE(dis) else skipValue(dis, valueType)
                        "general.file_type" -> fileType = when (valueType) {
                            TYPE_UINT32, TYPE_INT32 -> readIntLE(dis)
                            TYPE_UINT16, TYPE_INT16 -> readShortLE(dis)
                            TYPE_UINT8, TYPE_INT8 -> dis.readUnsignedByte()
                            else -> {
                                skipValue(dis, valueType)
                                null
                            }
                        }
                    }
                    remainingWanted--
                    if (remainingWanted <= 0) break
                } else {
                    skipValue(dis, valueType)
                }
            }

            GgufHeaderInfo(
                version = version,
                tensorCount = tensorCount,
                kvCount = kvCount,
                architecture = arch?.trim()?.takeIf { it.isNotEmpty() },
                modelName = name?.trim()?.takeIf { it.isNotEmpty() },
                fileType = fileType,
                fileTypeQuantTag = fileType?.let(::fileTypeToQuantTag)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun extractQuantTag(fileName: String, header: GgufHeaderInfo? = null): String? {
        val fromName = QUANT_REGEX.find(fileName)?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT)
        return fromName ?: header?.fileTypeQuantTag
    }

    fun classifyFile(fileName: String, header: GgufHeaderInfo? = null): LocalModelClassification {
        val lower = fileName.lowercase(Locale.ROOT)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val arch = header?.architecture?.lowercase(Locale.ROOT)
        val quant = extractQuantTag(fileName, header)
        val cleanStem = fileName.substringBeforeLast('.').take(64).ifBlank { fileName.take(64) }
        val baseTitle = header?.modelName?.takeIf { it.isNotBlank() }?.take(60) ?: cleanStem

        // 1. Recognized shared companion artifacts (VAE / TAE / TE / pi-H3 adapters)
        val companionKey = detectCompanionSharedKey(lower)
        if (companionKey != null && ext == "safetensors") {
            return LocalModelClassification(
                suggestedType = if (lower.contains("h3") || lower.contains("taew")) HubModelType.VideoWan else HubModelType.SdImage,
                confidence = LocalImportConfidence.High,
                displayName = companionKey,
                quantTag = quant,
                archHint = "companion",
                companionSharedKey = companionKey
            )
        }

        // 2. MiniMax-H3 video diffusion GGUF
        if (lower.contains("minimax_h3") || lower.contains("minimax-h3") || arch == "minimax_h3") {
            // Distinguish Qwen3-VL text encoder companion vs DiT
            if (lower.contains("qwen3vl") || lower.contains("qwen3-vl")) {
                return LocalModelClassification(
                    suggestedType = HubModelType.VideoWan,
                    confidence = LocalImportConfidence.High,
                    displayName = HubCompanionArtifacts.MINIMAX_H3_TE_Q2_K.sharedCompanionKey ?: cleanStem,
                    quantTag = quant,
                    archHint = "companion",
                    companionSharedKey = HubCompanionArtifacts.MINIMAX_H3_TE_Q2_K.sharedCompanionKey
                )
            }
            val isRef2Va = lower.contains("ref2va")
            val meta = mapOf(
                "arch" to "minimax_h3",
                "h3Mode" to if (isRef2Va) "ref2va" else "fl2va",
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
            )
            return LocalModelClassification(
                suggestedType = HubModelType.VideoWan,
                confidence = LocalImportConfidence.High,
                displayName = formatLocalDisplayName(baseTitle, quant),
                quantTag = quant,
                archHint = "minimax_h3",
                metadata = meta,
                expectedCompanionKeys = listOfNotNull(
                    HubCompanionArtifacts.MINIMAX_H3_TE_Q2_K.sharedCompanionKey,
                    HubCompanionArtifacts.MINIMAX_H3_VIDEO_VAE.sharedCompanionKey,
                    HubCompanionArtifacts.MINIMAX_H3_AUDIO_VAE.sharedCompanionKey,
                    HubCompanionArtifacts.PI_H3_2STEP_ADAPTER.sharedCompanionKey,
                    HubCompanionArtifacts.PI_H3_4STEP_ADAPTER.sharedCompanionKey
                )
            )
        }

        // 3. Wan2.2 / Wan2.1 video diffusion GGUF (and UMT5 text encoder companion)
        if (lower.contains("umt5") && lower.contains("encoder")) {
            return LocalModelClassification(
                suggestedType = HubModelType.VideoWan,
                confidence = LocalImportConfidence.High,
                displayName = HubCompanionArtifacts.UMT5_XXL_Q3_K_S.sharedCompanionKey ?: cleanStem,
                quantTag = quant,
                archHint = "companion",
                companionSharedKey = HubCompanionArtifacts.UMT5_XXL_Q3_K_S.sharedCompanionKey
            )
        }
        if (lower.contains("wan2.2") || lower.contains("wan2_2") || lower.contains("wan2.1") || lower.contains("wan2_1") || arch in VIDEO_ARCHS) {
            val isWan22 = lower.contains("wan2.2") || lower.contains("wan2_2")
            val isTurbo = lower.contains("turbo") || lower.contains("lightx2v") || lower.contains("fast")
            val tae = if (isWan22) HubCompanionArtifacts.TAE_WAN2_2 else HubCompanionArtifacts.TAE_WAN2_1
            val meta = mapOf(
                "arch" to if (isWan22) "wan22_ti2v" else "wan21_t2v",
                "t5Companion" to HubCompanionArtifacts.UMT5_XXL_Q3_K_S.sharedCompanionKey.orEmpty(),
                "taeCompanion" to tae.sharedCompanionKey.orEmpty(),
                "defaultSteps" to if (isTurbo) "4" else "10",
                "defaultCfg" to if (isTurbo) "1.0" else "5.0",
                "defaultShift" to if (isWan22) "5.0" else "3.0"
            )
            return LocalModelClassification(
                suggestedType = HubModelType.VideoWan,
                confidence = LocalImportConfidence.High,
                displayName = formatLocalDisplayName(baseTitle, quant),
                quantTag = quant,
                archHint = meta["arch"],
                metadata = meta,
                expectedCompanionKeys = listOfNotNull(
                    HubCompanionArtifacts.UMT5_XXL_Q3_K_S.sharedCompanionKey,
                    tae.sharedCompanionKey
                )
            )
        }

        // 4. Modular & single-file image diffusion checkpoints
        val modularImage = detectModularImageMetadata(lower)
        if (modularImage != null) {
            return LocalModelClassification(
                suggestedType = HubModelType.SdImage,
                confidence = LocalImportConfidence.High,
                displayName = formatLocalDisplayName(baseTitle, quant),
                quantTag = quant,
                archHint = modularImage.first["arch"],
                metadata = modularImage.first,
                expectedCompanionKeys = modularImage.second
            )
        }
        if (ext == "safetensors" || ext == "ckpt" || arch in IMAGE_ARCHS ||
            lower.contains("sdxl") || lower.contains("sd15") || lower.contains("sd_1.5") ||
            lower.contains("sd3") || lower.contains("dreamshaper") || lower.contains("juggernaut") ||
            lower.contains("realvis") || lower.contains("illustrious") || lower.contains("chroma")
        ) {
            return LocalModelClassification(
                suggestedType = HubModelType.SdImage,
                confidence = if (ext == "safetensors" || ext == "ckpt" || arch in IMAGE_ARCHS) LocalImportConfidence.High else LocalImportConfidence.Medium,
                displayName = formatLocalDisplayName(baseTitle, quant),
                quantTag = quant,
                archHint = arch ?: ext
            )
        }

        // 5. Music / Audio GGUF
        if (lower.contains("ace-step") || lower.contains("acestep") || lower.contains("yue") ||
            lower.contains("heartmula") || lower.contains("stable-audio") || lower.contains("stable_audio")
        ) {
            return LocalModelClassification(
                suggestedType = HubModelType.MusicAudio,
                confidence = LocalImportConfidence.Medium,
                displayName = formatLocalDisplayName(baseTitle, quant),
                quantTag = quant,
                archHint = arch
            )
        }

        // 6. Decision models (Elephant-1 / OneJev)
        val combinedName = (lower + " " + (header?.modelName?.lowercase(Locale.ROOT) ?: "")).trim()
        if (combinedName.contains("elephant") || combinedName.contains("onejev")) {
            return LocalModelClassification(
                suggestedType = HubModelType.DecisionGguf,
                confidence = LocalImportConfidence.High,
                displayName = formatLocalDisplayName(baseTitle, quant),
                quantTag = quant,
                archHint = arch
            )
        }

        // 7. Standard GGUF Chat LLM (confirmed by header general.architecture)
        if (arch != null && arch in LLM_ARCHS) {
            return LocalModelClassification(
                suggestedType = HubModelType.GgufChat,
                confidence = LocalImportConfidence.High,
                displayName = formatLocalDisplayName(baseTitle, quant),
                quantTag = quant,
                archHint = arch
            )
        }

        // 8. Fallback for unrecognized GGUF
        return LocalModelClassification(
            suggestedType = HubModelType.GgufChat,
            confidence = if (header != null) LocalImportConfidence.Medium else LocalImportConfidence.Low,
            displayName = formatLocalDisplayName(baseTitle, quant),
            quantTag = quant,
            archHint = arch
        )
    }

    private fun detectCompanionSharedKey(lower: String): String? = when {
        lower.contains("pi_h3_2step") || lower.contains("pi-h3-2step") -> HubCompanionArtifacts.PI_H3_2STEP_ADAPTER.sharedCompanionKey
        lower.contains("pi_h3_4step") || lower.contains("pi-h3-4step") -> HubCompanionArtifacts.PI_H3_4STEP_ADAPTER.sharedCompanionKey
        lower.contains("minimax_h3_video_vae") -> HubCompanionArtifacts.MINIMAX_H3_VIDEO_VAE.sharedCompanionKey
        lower.contains("minimax_h3_audio_vae") -> HubCompanionArtifacts.MINIMAX_H3_AUDIO_VAE.sharedCompanionKey
        lower.contains("taew2_2") -> HubCompanionArtifacts.TAE_WAN2_2.sharedCompanionKey
        lower.contains("taew2_1") -> HubCompanionArtifacts.TAE_WAN2_1.sharedCompanionKey
        lower.contains("flux2") && lower.contains("ae") -> HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey
        lower == "ae.safetensors" || lower.contains("flux1_ae") || lower.contains("flux_ae") -> HubCompanionArtifacts.FLUX1_AE_VAE.sharedCompanionKey
        else -> null
    }

    private fun detectModularImageMetadata(lower: String): Pair<Map<String, String>, List<String>>? {
        val isBase = lower.contains("base")
        val isTurbo = lower.contains("turbo")
        return when {
            lower.contains("flux-3") || lower.contains("flux3") || lower.contains("flux_3") -> {
                val llm = HubCompanionArtifacts.QWEN3_VL_4B_INSTRUCT.sharedCompanionKey.orEmpty()
                val vae = HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty()
                mapOf(
                    "arch" to "flux3_klein",
                    "llmCompanion" to llm,
                    "vaeCompanion" to vae,
                    "supportsEdit" to "true",
                    "defaultSteps" to if (isBase) "20" else "16",
                    "defaultCfg" to if (isBase) "3.5" else "1.0"
                ) to listOf(llm, vae).filter { it.isNotEmpty() }
            }
            lower.contains("ernie-image") || lower.contains("ernie_image") -> {
                val llm = HubCompanionArtifacts.MINISTRAL_3_3B_INSTRUCT.sharedCompanionKey.orEmpty()
                val vae = HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty()
                mapOf(
                    "arch" to "ernie_image",
                    "llmCompanion" to llm,
                    "vaeCompanion" to vae,
                    "defaultSteps" to if (isTurbo) "8" else "20",
                    "defaultCfg" to if (isTurbo) "1.0" else "5.0"
                ) to listOf(llm, vae).filter { it.isNotEmpty() }
            }
            lower.contains("z-image") || lower.contains("z_image") -> {
                val llm = HubCompanionArtifacts.QWEN3_4B_INSTRUCT_2507.sharedCompanionKey.orEmpty()
                val vae = HubCompanionArtifacts.FLUX1_AE_VAE.sharedCompanionKey.orEmpty()
                mapOf(
                    "arch" to "z_image",
                    "llmCompanion" to llm,
                    "vaeCompanion" to vae,
                    "defaultSteps" to if (isTurbo) "8" else "20",
                    "defaultCfg" to if (isTurbo) "1.0" else "4.5"
                ) to listOf(llm, vae).filter { it.isNotEmpty() }
            }
            (lower.contains("flux-2") || lower.contains("flux2") || lower.contains("flux_2")) && lower.contains("9b") -> {
                val llm = HubCompanionArtifacts.QWEN3_8B_BASE_TE.sharedCompanionKey.orEmpty()
                val vae = HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty()
                mapOf(
                    "arch" to "flux2_klein_9b",
                    "llmCompanion" to llm,
                    "vaeCompanion" to vae,
                    "supportsEdit" to "true",
                    "defaultSteps" to if (isBase) "20" else "4",
                    "defaultCfg" to if (isBase) "4.0" else "1.0"
                ) to listOf(llm, vae).filter { it.isNotEmpty() }
            }
            lower.contains("flux-2") || lower.contains("flux2") || lower.contains("flux_2") -> {
                val llm = HubCompanionArtifacts.QWEN3_4B_BASE_TE.sharedCompanionKey.orEmpty()
                val vae = HubCompanionArtifacts.FLUX2_AE_VAE.sharedCompanionKey.orEmpty()
                mapOf(
                    "arch" to "flux2_klein",
                    "llmCompanion" to llm,
                    "vaeCompanion" to vae,
                    "supportsEdit" to "true",
                    "defaultSteps" to if (isBase) "20" else "4",
                    "defaultCfg" to if (isBase) "4.0" else "1.0"
                ) to listOf(llm, vae).filter { it.isNotEmpty() }
            }
            else -> null
        }
    }

    private fun formatLocalDisplayName(base: String, quant: String?): String {
        val trimmed = base.trim().take(64)
        return if (!quant.isNullOrBlank() && !trimmed.contains(quant, ignoreCase = true)) {
            "$trimmed · $quant".take(80)
        } else {
            trimmed.take(80)
        }
    }

    private fun fileTypeToQuantTag(ft: Int): String? = when (ft) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "Q2_K_S"
        22 -> "IQ3_XS"
        23 -> "IQ3_XXS"
        24 -> "IQ1_S"
        25 -> "IQ4_NL"
        26 -> "IQ3_S"
        27 -> "IQ3_M"
        28 -> "IQ2_S"
        29 -> "IQ2_M"
        30 -> "IQ4_XS"
        31 -> "IQ1_M"
        32 -> "BF16"
        else -> null
    }

    private fun readKeyOrSkip(dis: DataInputStream): String? {
        val len = readLongLE(dis)
        if (len < 0L || len > MAX_STRING_BYTES) throw EOFException("Invalid GGUF key length: $len")
        if (len > 128L) {
            skipExactly(dis, len)
            return null
        }
        val bytes = ByteArray(len.toInt())
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readStringLE(dis: DataInputStream): String {
        val len = readLongLE(dis)
        if (len < 0L || len > MAX_STRING_BYTES) throw EOFException("Invalid GGUF string length: $len")
        val bytes = ByteArray(len.toInt())
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipValue(dis: DataInputStream, valueType: Int) {
        when (valueType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skipExactly(dis, 1)
            TYPE_UINT16, TYPE_INT16 -> skipExactly(dis, 2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skipExactly(dis, 4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skipExactly(dis, 8)
            TYPE_STRING -> {
                val len = readLongLE(dis)
                if (len < 0L || len > 64L * 1024 * 1024) throw EOFException("Invalid string len: $len")
                skipExactly(dis, len)
            }
            TYPE_ARRAY -> {
                val elemType = readIntLE(dis)
                val count = readLongLE(dis)
                if (count < 0L || count > MAX_ARRAY_ELEMENTS) throw EOFException("Invalid array count: $count")
                val fixed = fixedTypeSize(elemType)
                if (fixed > 0) {
                    skipExactly(dis, count * fixed)
                } else {
                    for (j in 0L until count) skipValue(dis, elemType)
                }
            }
            else -> throw EOFException("Unknown GGUF value type: $valueType")
        }
    }

    private fun fixedTypeSize(type: Int): Int = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
        TYPE_UINT16, TYPE_INT16 -> 2
        TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
        TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
        else -> -1
    }

    private fun skipExactly(dis: DataInputStream, bytes: Long) {
        var rem = bytes
        while (rem > 0L) {
            val skipped = dis.skip(rem)
            if (skipped > 0L) {
                rem -= skipped
            } else {
                if (dis.read() == -1) throw EOFException("Unexpected EOF while skipping $bytes bytes")
                rem--
            }
        }
    }

    private fun readShortLE(dis: DataInputStream): Int {
        val b0 = dis.readUnsignedByte()
        val b1 = dis.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readIntLE(dis: DataInputStream): Int {
        val b0 = dis.readUnsignedByte()
        val b1 = dis.readUnsignedByte()
        val b2 = dis.readUnsignedByte()
        val b3 = dis.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLongLE(dis: DataInputStream): Long {
        val lo = readIntLE(dis).toLong() and 0xFFFF_FFFFL
        val hi = readIntLE(dis).toLong() and 0xFFFF_FFFFL
        return lo or (hi shl 32)
    }
}
