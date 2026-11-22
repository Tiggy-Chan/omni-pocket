package com.tiggy.qwenimage.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.tiggy.qwenimage.ChatModelChoice
import com.tiggy.qwenimage.DecisionModelChoice
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.AppLanguage
import com.tiggy.qwenimage.data.AppLanguageManager
import com.tiggy.qwenimage.data.BenchmarkModality
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.DeviceFitLevel
import com.tiggy.qwenimage.data.DeviceHardwareSnapshot
import com.tiggy.qwenimage.data.EvidenceTier
import com.tiggy.qwenimage.data.HfFileFormat
import com.tiggy.qwenimage.data.HfRepoOption
import com.tiggy.qwenimage.data.HfRepoSummary
import com.tiggy.qwenimage.data.HubCompanionNote
import com.tiggy.qwenimage.data.HubMirrorMode
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.HubProbeResult
import com.tiggy.qwenimage.data.HubSearchCategory
import com.tiggy.qwenimage.data.HubSettings
import com.tiggy.qwenimage.data.HubSplitWarning
import com.tiggy.qwenimage.data.LlmModelStatus
import com.tiggy.qwenimage.data.ModelBenchmarkEntry
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.MossModelStatus
import com.tiggy.qwenimage.data.PackagedModelStatus
import com.tiggy.qwenimage.data.QuantHintKey
import com.tiggy.qwenimage.data.QuantQualityHint
import com.tiggy.qwenimage.data.ThemeMode
import com.tiggy.qwenimage.data.formatBytesCompact
import com.tiggy.qwenimage.engine.KvCachePrecision

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.System }
val LocalLocalizedContext = staticCompositionLocalOf<Context> {
    error("LocalLocalizedContext must be provided via ProvideOmniLocalization")
}

@Composable
fun ProvideOmniLocalization(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val base = LocalContext.current
    val localized = remember(base, language) {
        AppLanguageManager.localizedContext(base, language)
    }
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalLocalizedContext provides localized,
        content = content
    )
}

@Composable
fun tr(@StringRes id: Int): String = LocalLocalizedContext.current.getString(id)

@Composable
fun tr(@StringRes id: Int, vararg args: Any): String = LocalLocalizedContext.current.getString(id, *args)

@Composable
fun AppLanguage.displayLabel(): String = when (this) {
    AppLanguage.System -> {
        val resolved = AppLanguageManager.effectiveLanguage(AppLanguage.System)
        tr(R.string.lang_follow_system, resolved.nativeName)
    }
    else -> nativeName
}

@Composable
fun AppDomain.localizedTitle(): String = when (this) {
    AppDomain.Conversation -> tr(R.string.nav_chat)
    AppDomain.Decision -> tr(R.string.nav_decision)
    AppDomain.ImageStudio -> tr(R.string.nav_image)
    AppDomain.MusicStudio -> tr(R.string.nav_music)
    AppDomain.VideoStudio -> tr(R.string.nav_video)
}

@Composable
fun ThemeMode.localizedLabel(): String = when (this) {
    ThemeMode.System -> tr(R.string.theme_system)
    ThemeMode.Light -> tr(R.string.theme_light)
    ThemeMode.Dark -> tr(R.string.theme_dark)
}

@Composable
fun KvCachePrecision.localizedDetail(): String = when (this) {
    KvCachePrecision.Fp16 -> tr(R.string.kv_fp16_detail)
    KvCachePrecision.Q8_0 -> tr(R.string.kv_q8_detail)
    KvCachePrecision.Q4_0 -> tr(R.string.kv_q4_detail)
}

@Composable
fun CoTMode.localizedLabel(): String = when (this) {
    CoTMode.Melody -> tr(R.string.cot_melody)
    CoTMode.Full -> tr(R.string.cot_full)
    CoTMode.Off -> tr(R.string.cot_off)
}

@Composable
fun HubMirrorMode.localizedLabel(): String = when (this) {
    HubMirrorMode.Official -> "Hugging Face"
    HubMirrorMode.HfMirror -> tr(R.string.mirror_hf_mirror)
    HubMirrorMode.Custom -> tr(R.string.common_custom)
}

@Composable
fun HubSettings.localizedDisplayName(): String = when (mode) {
    HubMirrorMode.Official -> "Hugging Face"
    HubMirrorMode.HfMirror -> "HF-Mirror"
    HubMirrorMode.Custom -> customBaseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        .ifBlank { tr(R.string.common_custom) }
}

@Composable
fun HubSearchCategory.localizedLabel(): String = when (this) {
    HubSearchCategory.GgufChat -> tr(R.string.models_cat_gguf)
    HubSearchCategory.MnnChat -> tr(R.string.models_cat_mnn)
    HubSearchCategory.DecisionGguf -> tr(R.string.models_cat_decision)
    HubSearchCategory.SdImage -> tr(R.string.nav_image)
    HubSearchCategory.VideoWan -> tr(R.string.nav_video)
    HubSearchCategory.MusicAudio -> tr(R.string.nav_music)
}

@Composable
fun HubSearchCategory.localizedHint(): String = when (this) {
    HubSearchCategory.GgufChat -> tr(R.string.models_hint_gguf)
    HubSearchCategory.MnnChat -> tr(R.string.models_hint_mnn)
    HubSearchCategory.DecisionGguf -> tr(R.string.models_hint_decision)
    HubSearchCategory.SdImage -> tr(R.string.models_hint_image)
    HubSearchCategory.VideoWan -> tr(R.string.models_hint_video)
    HubSearchCategory.MusicAudio -> tr(R.string.models_hint_music)
}

@Composable
fun HubSearchCategory.localizedEmptyMessage(): String = when (this) {
    HubSearchCategory.GgufChat -> tr(R.string.hub_empty_gguf)
    HubSearchCategory.MnnChat -> tr(R.string.hub_empty_mnn)
    HubSearchCategory.DecisionGguf -> tr(R.string.hub_empty_decision)
    HubSearchCategory.SdImage -> tr(R.string.hub_empty_image)
    HubSearchCategory.VideoWan -> tr(R.string.hub_empty_video)
    HubSearchCategory.MusicAudio -> tr(R.string.hub_empty_music)
}

@Composable
fun HubModelType.localizedLabel(): String = when (this) {
    HubModelType.GgufChat -> tr(R.string.models_cat_gguf)
    HubModelType.MnnChat -> tr(R.string.models_cat_mnn)
    HubModelType.DecisionGguf -> tr(R.string.models_cat_decision)
    HubModelType.SdImage -> tr(R.string.nav_image)
    HubModelType.VideoWan -> tr(R.string.nav_video)
    HubModelType.MusicAudio -> tr(R.string.nav_music)
}

@Composable
fun BenchmarkModality.localizedLabel(): String = when (this) {
    BenchmarkModality.Chat -> tr(R.string.nav_chat)
    BenchmarkModality.Decision -> tr(R.string.nav_decision)
    BenchmarkModality.Image -> tr(R.string.nav_image)
    BenchmarkModality.Music -> tr(R.string.nav_music)
    BenchmarkModality.Video -> tr(R.string.nav_video)
}

@Composable
fun EvidenceTier.localizedLabel(): String = when (this) {
    EvidenceTier.PublicLeaderboard -> tr(R.string.evidence_leaderboard)
    EvidenceTier.OfficialReport -> tr(R.string.evidence_official)
    EvidenceTier.EdgeReference -> tr(R.string.evidence_edge)
}

@Composable
fun DeviceFitLevel.localizedLabel(): String = when (this) {
    DeviceFitLevel.Smooth -> tr(R.string.fit_smooth)
    DeviceFitLevel.Usable -> tr(R.string.fit_usable)
    DeviceFitLevel.Tight -> tr(R.string.fit_tight)
    DeviceFitLevel.LikelyOom -> tr(R.string.fit_oom)
}

@Composable
fun DeviceHardwareSnapshot.localizedSummary(): String {
    val accel = buildList {
        if (hasVulkan) add("Vulkan")
        if (hasOpenCl) add("OpenCL")
    }.ifEmpty { listOf("CPU") }.joinToString(" / ")
    val ramGb = kotlin.math.round(totalRamGb).toInt().coerceAtLeast(1)
    return tr(R.string.hardware_summary_fmt, ramGb, cpuCores, accel)
}

@Composable
fun QuantQualityHint.localizedNote(): String = when (key) {
    QuantHintKey.Lossless -> tr(R.string.quant_note_lossless)
    QuantHintKey.Q8 -> tr(R.string.quant_note_q8)
    QuantHintKey.Q6 -> tr(R.string.quant_note_q6)
    QuantHintKey.Q5High -> tr(R.string.quant_note_q5_high)
    QuantHintKey.Q5 -> tr(R.string.quant_note_q5)
    QuantHintKey.Q4SweetSpot -> tr(R.string.quant_note_q4_sweet)
    QuantHintKey.Q4Compact -> tr(R.string.quant_note_q4_compact)
    QuantHintKey.Q4Fast -> tr(R.string.quant_note_q4_fast)
    QuantHintKey.Q3Balanced -> tr(R.string.quant_note_q3_balanced)
    QuantHintKey.Q3Compact -> tr(R.string.quant_note_q3_compact)
    QuantHintKey.Q2Fallback -> tr(R.string.quant_note_q2_fallback)
}

@Composable
fun HfRepoOption.localizedLabel(): String =
    if (format == HfFileFormat.MnnBundle) tr(R.string.hub_mnn_bundle_fmt, repo.substringAfter('/'))
    else label

@Composable
fun HfRepoOption.localizedCompanionSummary(): String? = when (companionNoteKey) {
    HubCompanionNote.ZImageShared -> tr(R.string.hub_comp_zimage)
    HubCompanionNote.Flux2Shared -> tr(R.string.hub_comp_flux2)
    HubCompanionNote.WanShared -> tr(R.string.hub_comp_wan, companionNoteArg.orEmpty())
    HubCompanionNote.Yue2Sidecars -> tr(R.string.hub_comp_yue2, companionNoteArg.orEmpty())
    HubCompanionNote.SelfContainedAudioGguf -> tr(R.string.hub_comp_audio_single)
    HubCompanionNote.DecisionBlockBidir -> tr(R.string.hub_comp_dec_bidir)
    HubCompanionNote.DecisionCausal -> tr(R.string.hub_comp_dec_causal)
    null -> companionSummary
}

@Composable
fun HfRepoOption.localizedSplitWarning(): String? = when (splitWarningKey) {
    HubSplitWarning.SplitGguf -> tr(R.string.hub_split_gguf)
    HubSplitWarning.WanMoE14B -> tr(R.string.hub_wan_moe14b)
    null -> splitWarning
}

@Composable
fun HfRepoSummary.localizedSummary(): String {
    val resId = when (summaryKey) {
        "curated_qwen3_06b" -> R.string.curated_qwen3_06b
        "curated_qwen3_17b" -> R.string.curated_qwen3_17b
        "curated_qwen3_4b" -> R.string.curated_qwen3_4b
        "curated_qwen25_3b" -> R.string.curated_qwen25_3b
        "curated_gemma3_4b" -> R.string.curated_gemma3_4b
        "curated_gemma3_1b" -> R.string.curated_gemma3_1b
        "curated_dsr1_15b" -> R.string.curated_dsr1_15b
        "curated_llama32_3b" -> R.string.curated_llama32_3b
        "curated_mnn_qwen3_17b" -> R.string.curated_mnn_qwen3_17b
        "curated_mnn_qwen3_4b" -> R.string.curated_mnn_qwen3_4b
        "curated_mnn_dsr1_15b" -> R.string.curated_mnn_dsr1_15b
        "curated_mnn_qwen25_3b" -> R.string.curated_mnn_qwen25_3b
        "curated_onejev_08b" -> R.string.curated_onejev_08b
        "curated_onejev_4b" -> R.string.curated_onejev_4b
        "curated_onejev_9b" -> R.string.curated_onejev_9b
        "curated_elephant_1" -> R.string.curated_elephant_1
        "curated_elephant_mirror" -> R.string.curated_elephant_mirror
        "curated_zimage" -> R.string.curated_zimage
        "curated_flux2" -> R.string.curated_flux2
        "curated_sdxl_turbo" -> R.string.curated_sdxl_turbo
        "curated_sd_turbo" -> R.string.curated_sd_turbo
        "curated_dreamshaper" -> R.string.curated_dreamshaper
        "curated_sd15" -> R.string.curated_sd15
        "curated_wan22_turbo" -> R.string.curated_wan22_turbo
        "curated_wan22_qs" -> R.string.curated_wan22_qs
        "curated_wan22_unsloth" -> R.string.curated_wan22_unsloth
        "curated_wan21_13b" -> R.string.curated_wan21_13b
        "curated_wan21_offgrid" -> R.string.curated_wan21_offgrid
        "curated_audiocpp" -> R.string.curated_audiocpp
        "curated_yue2" -> R.string.curated_yue2
        else -> null
    }
    return if (resId != null) tr(resId) else summary
}

@Composable
fun ModelBenchmarkEntry.localizedStrengths(): String {
    val resId = when (id) {
        "qwen3.5-9b" -> R.string.bench_str_qwen35_9b
        "qwen3-4b" -> R.string.bench_str_qwen3_4b
        "qwen3.5-4b" -> R.string.bench_str_qwen35_4b
        "deepseek-r1-distill-qwen-1.5b" -> R.string.bench_str_dsr1_15b
        "qwen3.5-2b" -> R.string.bench_str_qwen35_2b
        "phi-4-mini" -> R.string.bench_str_phi4_mini
        "qwen3-1.7b" -> R.string.bench_str_qwen3_17b
        "gemma-3-4b" -> R.string.bench_str_gemma3_4b
        "qwen2.5-3b" -> R.string.bench_str_qwen25_3b
        "llama-3.2-3b" -> R.string.bench_str_llama32_3b
        "qwen3.5-0.8b" -> R.string.bench_str_qwen35_08b
        "qwen3-0.6b" -> R.string.bench_str_qwen3_06b
        "gemma-3-1b" -> R.string.bench_str_gemma3_1b
        "elephant-1-9b" -> R.string.bench_str_elephant1
        "onejev-1.6-4b" -> R.string.bench_str_onejev4b
        "onejev-1.6-0.8b" -> R.string.bench_str_onejev08b
        "qwen-image-2.1" -> R.string.bench_str_qwen_img
        "z-image-turbo" -> R.string.bench_str_zimage
        "flux2-klein-4b" -> R.string.bench_str_flux2
        "moss-image" -> R.string.bench_str_moss
        "sdxl-turbo" -> R.string.bench_str_sdxl_turbo
        "sd-turbo-15" -> R.string.bench_str_sd15
        "ace-step-1.5" -> R.string.bench_str_acestep
        "yue2-3b" -> R.string.bench_str_yue2
        "heartmula-3b" -> R.string.bench_str_heartmula
        "stable-audio-3-small" -> R.string.bench_str_stable_audio
        "wan2.2-ti2v-5b" -> R.string.bench_str_wan22
        "wan2.1-t2v-1.3b" -> R.string.bench_str_wan21
        else -> null
    }
    return if (resId != null) tr(resId) else strengths
}

@Composable
fun LlmModelStatus.localizedStatus(): String = when {
    ready -> tr(R.string.models_verified_size, formatBytesCompact(spec.totalBytes))
    present == spec.artifacts.size -> "${tr(R.string.status_needs_verify)} · ${formatBytesCompact(spec.totalBytes)}"
    present > 0 -> "${tr(R.string.status_files_progress, present, spec.artifacts.size)} · ${formatBytesCompact(spec.totalBytes)}"
    else -> "${tr(R.string.common_not_downloaded)} · ${formatBytesCompact(spec.totalBytes)}"
}

@Composable
fun MossModelStatus.localizedStatus(): String = when {
    ready -> tr(R.string.status_ready_local)
    total > 0 && present > 0 -> tr(R.string.status_files_need_verify, present, total)
    else -> tr(R.string.status_moss_prompt)
}

@Composable
fun ModelStatus.localizedStatus(): String = when {
    ready -> tr(R.string.status_ready_local)
    total > 0 && present > 0 -> tr(R.string.status_files_need_verify, present, total)
    else -> tr(R.string.status_qwen_prompt)
}

@Composable
fun PackagedModelStatus.localizedStatus(): String {
    val stateText = when {
        verified -> tr(R.string.models_verified_size, formatBytesCompact(totalBytes))
        ready -> "${tr(R.string.status_complete_verify_hint)} · ${formatBytesCompact(totalBytes)}"
        present > 0 -> "${tr(R.string.status_files_progress, present, total)} · ${formatBytesCompact(totalBytes)}"
        else -> "${tr(R.string.common_not_downloaded)} · ${formatBytesCompact(totalBytes)}"
    }
    return stateText
}

@Composable
fun ChatModelChoice.localizedDetail(): String = when {
    ready -> tr(R.string.status_ready_backend_size, backend.label, formatBytesCompact(totalBytes))
    !builtIn -> tr(R.string.status_missing_file)
    else -> "${tr(R.string.common_not_downloaded)} · ${formatBytesCompact(totalBytes)}"
}

@Composable
fun DecisionModelChoice.localizedDetail(): String {
    val sizeStr = formatBytesCompact(totalBytes)
    val tag = if (builtIn) "GGUF" else "llama.cpp"
    return when {
        ready -> tr(R.string.status_ready_backend_size, tag, sizeStr)
        !builtIn -> tr(R.string.status_missing_file)
        else -> "${tr(R.string.common_not_downloaded)} · $sizeStr"
    }
}

@Composable
fun HubProbeResult.localizedMessage(): String = when {
    reachable -> tr(R.string.hub_probe_ok_fmt, latencyMs)
    httpCode != null -> tr(R.string.hub_probe_http_fmt, httpCode)
    else -> tr(R.string.hub_probe_fail_fmt, errorDetail ?: message)
}
