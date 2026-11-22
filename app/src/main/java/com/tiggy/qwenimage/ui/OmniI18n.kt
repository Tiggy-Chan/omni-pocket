package com.tiggy.qwenimage.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.AppLanguage
import com.tiggy.qwenimage.data.AppLanguageManager
import com.tiggy.qwenimage.data.BenchmarkModality
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.DeviceFitLevel
import com.tiggy.qwenimage.data.DeviceHardwareSnapshot
import com.tiggy.qwenimage.data.EvidenceTier
import com.tiggy.qwenimage.data.HubMirrorMode
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.HubSearchCategory
import com.tiggy.qwenimage.data.HubSettings
import com.tiggy.qwenimage.data.ThemeMode
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
    HubSearchCategory.SdImage -> tr(R.string.nav_image)
    HubSearchCategory.VideoWan -> tr(R.string.nav_video)
    HubSearchCategory.MusicAudio -> tr(R.string.nav_music)
}

@Composable
fun HubModelType.localizedLabel(): String = when (this) {
    HubModelType.GgufChat -> tr(R.string.models_cat_gguf)
    HubModelType.MnnChat -> tr(R.string.models_cat_mnn)
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
