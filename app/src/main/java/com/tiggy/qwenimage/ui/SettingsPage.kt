package com.tiggy.qwenimage.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.ModelHubViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.AppLanguage
import com.tiggy.qwenimage.data.AppLanguageManager
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.LlmConversation
import com.tiggy.qwenimage.data.ThemeMode
import com.tiggy.qwenimage.engine.GgufComputeBackend
import com.tiggy.qwenimage.engine.KvCachePrecision
import com.tiggy.qwenimage.engine.SUPPORTED_SD_SAMPLERS
import com.tiggy.qwenimage.engine.ThermalPowerProfile
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsTab { General, Chat, Decision, Image, Video, Music }

private val ContextWindowPresets = listOf(2048, 4096, 8192, 16384, 32768, 65536)
private val MaxNewTokenPresets = listOf(512, 1024, 2048, 4096, 8192)
private val VideoFramePresets = listOf(9, 17, 25, 33)
private val MiniMaxH3FramePresets = listOf(5, 22, 39, 56)
private val MusicDurationPresets = listOf(15, 30, 60, 90)

private data class VideoResolutionPreset(val width: Int, val height: Int, val labelRes: Int)

private val VideoResolutionPresets = listOf(
    VideoResolutionPreset(480, 832, R.string.res_portrait),
    VideoResolutionPreset(832, 480, R.string.res_landscape),
    VideoResolutionPreset(480, 480, R.string.res_square),
    VideoResolutionPreset(384, 640, R.string.res_preview)
)

private val MiniMaxH3ResolutionPresets = listOf(
    VideoResolutionPreset(480, 288, R.string.res_h3_landscape),
    VideoResolutionPreset(288, 480, R.string.res_h3_portrait),
    VideoResolutionPreset(512, 320, R.string.res_h3_wide),
    VideoResolutionPreset(640, 384, R.string.res_h3_hd)
)

@Composable
fun SettingsPage(
    state: QwenUiState,
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    decisionViewModel: DecisionViewModel,
    hubViewModel: ModelHubViewModel,
    onMenu: (() -> Unit)?
) {
    val context = LocalContext.current
    val localized = LocalLocalizedContext.current
    val focusManager = LocalFocusManager.current
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val decisionState by decisionViewModel.state.collectAsStateWithLifecycle()
    val hubState by hubViewModel.state.collectAsStateWithLifecycle()
    val appLanguage = LocalAppLanguage.current
    var activeTab by rememberSaveable { mutableStateOf(SettingsTab.General) }
    var showMirrorSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    var contextInput by remember { mutableStateOf(llmState.contextTokens.toString()) }
    var contextInputError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(llmState.contextTokens) {
        if (contextInput.toIntOrNull() != llmState.contextTokens) {
            contextInput = llmState.contextTokens.toString()
            contextInputError = null
        }
    }

    var maxOutInput by remember { mutableStateOf(llmState.maxNewTokens.toString()) }
    var maxOutInputError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(llmState.maxNewTokens) {
        if (maxOutInput.toIntOrNull() != llmState.maxNewTokens) {
            maxOutInput = llmState.maxNewTokens.toString()
            maxOutInputError = null
        }
    }

    var systemPromptExpanded by remember { mutableStateOf(false) }
    val ctxRangeErr = tr(R.string.settings_int_range_error, LlmViewModel.MIN_CONTEXT_TOKENS, LlmViewModel.MAX_CONTEXT_TOKENS)
    val outRangeErr = tr(R.string.settings_int_range_error, LlmViewModel.MIN_MAX_NEW_TOKENS, LlmViewModel.MAX_MAX_NEW_TOKENS)

    val applyCustomContext = {
        val parsed = contextInput.trim().toIntOrNull()
        if (parsed == null || parsed !in LlmViewModel.MIN_CONTEXT_TOKENS..LlmViewModel.MAX_CONTEXT_TOKENS) {
            contextInputError = ctxRangeErr
        } else {
            contextInputError = null
            llmViewModel.setContextTokens(parsed)
            focusManager.clearFocus()
        }
    }

    val applyCustomMaxOut = {
        val parsed = maxOutInput.trim().toIntOrNull()
        if (parsed == null || parsed !in LlmViewModel.MIN_MAX_NEW_TOKENS..LlmViewModel.MAX_MAX_NEW_TOKENS) {
            maxOutInputError = outRangeErr
        } else {
            maxOutInputError = null
            llmViewModel.setMaxNewTokens(parsed)
            focusManager.clearFocus()
        }
    }

    WorkspaceScaffold(title = tr(R.string.nav_settings), onMenu = onMenu) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                // Paged category tabs (prevents endless vertical scrolling)
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(SettingsTab.entries) { tab ->
                        val label = when (tab) {
                            SettingsTab.General -> tr(R.string.settings_tab_general)
                            SettingsTab.Chat -> tr(R.string.nav_chat)
                            SettingsTab.Decision -> tr(R.string.nav_decision)
                            SettingsTab.Image -> tr(R.string.nav_image)
                            SettingsTab.Video -> tr(R.string.nav_video)
                            SettingsTab.Music -> tr(R.string.nav_music)
                        }
                        ComposerChip(
                            label = label,
                            onClick = { activeTab = tab },
                            selected = activeTab == tab
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = padding.calculateBottomPadding() + 28.dp
                    )
                ) {
                    when (activeTab) {
                        SettingsTab.General -> {
                            item(key = "appearance") {
                                SectionLabel(tr(R.string.settings_appearance))
                                Text(
                                    tr(R.string.settings_theme),
                                    color = OmniColors.Ink,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                val themeLabels = mapOf(
                                    ThemeMode.System to ThemeMode.System.localizedLabel(),
                                    ThemeMode.Light to ThemeMode.Light.localizedLabel(),
                                    ThemeMode.Dark to ThemeMode.Dark.localizedLabel()
                                )
                                OmniTabs(
                                    items = ThemeMode.entries,
                                    selected = state.themeMode,
                                    label = { themeLabels[it] ?: it.name },
                                    onSelect = viewModel::setThemeMode
                                )
                                Spacer(Modifier.height(10.dp))
                                SettingRow(
                                    title = tr(R.string.settings_language),
                                    detail = appLanguage.displayLabel(),
                                    onClick = { showLanguageSheet = true },
                                    trailing = {
                                        TextButton(onClick = { showLanguageSheet = true }) {
                                            Text(tr(R.string.common_change))
                                        }
                                    }
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                            }
                            item(key = "hardware-source") {
                                SectionLabel(tr(R.string.settings_device_source), Modifier.padding(top = 18.dp))
                                hubState.deviceHardware?.let { hw ->
                                    val socSuffix = if (hw.socLabel.isNotBlank()) " · ${hw.socLabel}" else ""
                                    SettingRow(
                                        title = tr(R.string.settings_hardware),
                                        detail = "${hw.localizedSummary()}$socSuffix"
                                    )
                                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                                }
                                SettingRow(
                                    title = tr(R.string.settings_hub_mirror),
                                    detail = "${hubState.hubSettings.localizedDisplayName()} · ${hubState.hubSettings.effectiveBaseUrl}",
                                    onClick = { showMirrorSheet = true },
                                    trailing = {
                                        TextButton(onClick = { showMirrorSheet = true }) {
                                            Text(tr(R.string.common_change))
                                        }
                                    }
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                            }
                        }

                        SettingsTab.Chat -> {
                            item(key = "chat-inference") {
                                SectionLabel(tr(R.string.settings_chat_inference))
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(tr(R.string.settings_max_context), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(ContextWindowPresets) { tokens ->
                                            ComposerChip(
                                                label = formatTokenCount(tokens),
                                                onClick = {
                                                    contextInputError = null
                                                    llmViewModel.setContextTokens(tokens)
                                                },
                                                selected = llmState.contextTokens == tokens
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OmniTextField(
                                            value = contextInput,
                                            onValueChange = {
                                                contextInput = it.filter(Char::isDigit).take(6)
                                                contextInputError = null
                                            },
                                            label = tr(R.string.settings_custom_context_label),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(onDone = { applyCustomContext() }),
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = applyCustomContext,
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) { Text(tr(R.string.common_apply)) }
                                    }
                                    contextInputError?.let {
                                        Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.labelSmall)
                                    }
                                    val trainHint = buildList {
                                        add(tr(R.string.settings_context_current, llmState.contextTokens))
                                        if (llmState.loadedTrainContext > 0) {
                                            add(tr(R.string.settings_context_loaded, llmState.loadedTrainContext, llmState.loadedEffectiveContext))
                                        }
                                        add(tr(R.string.settings_context_mnn_note))
                                    }.joinToString(" · ")
                                    Text(trainHint, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)

                                    Spacer(Modifier.height(2.dp))
                                    Text(tr(R.string.settings_max_output), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(MaxNewTokenPresets) { tokens ->
                                            ComposerChip(
                                                label = "$tokens",
                                                onClick = {
                                                    maxOutInputError = null
                                                    llmViewModel.setMaxNewTokens(tokens)
                                                },
                                                selected = llmState.maxNewTokens == tokens
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OmniTextField(
                                            value = maxOutInput,
                                            onValueChange = {
                                                maxOutInput = it.filter(Char::isDigit).take(5)
                                                maxOutInputError = null
                                            },
                                            label = tr(R.string.settings_custom_output_label),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(onDone = { applyCustomMaxOut() }),
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = applyCustomMaxOut,
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) { Text(tr(R.string.common_apply)) }
                                    }
                                    maxOutInputError?.let {
                                        Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.labelSmall)
                                    }

                                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 4.dp))

                                    SettingRow(
                                        title = tr(R.string.settings_temperature),
                                        detail = if (llmState.temperature <= 0.01f) tr(R.string.settings_temperature_greedy)
                                        else String.format(Locale.US, "%.2f", llmState.temperature)
                                    )
                                    Slider(
                                        value = llmState.temperature,
                                        onValueChange = { llmViewModel.setTemperature((it * 20).roundToInt() / 20f) },
                                        valueRange = 0f..1.5f
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_top_p),
                                        detail = String.format(Locale.US, "%.2f", llmState.topP)
                                    )
                                    Slider(
                                        value = llmState.topP,
                                        onValueChange = { llmViewModel.setTopP((it * 20).roundToInt() / 20f) },
                                        valueRange = 0.1f..1.0f
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_repeat_penalty),
                                        detail = String.format(Locale.US, "%.2f", llmState.repeatPenalty)
                                    )
                                    Slider(
                                        value = llmState.repeatPenalty,
                                        onValueChange = { llmViewModel.setRepeatPenalty((it * 20).roundToInt() / 20f) },
                                        valueRange = 1.0f..1.4f
                                    )

                                    SettingRow(
                                        title = tr(R.string.settings_system_prompt),
                                        detail = if (llmState.systemPrompt == LlmConversation.SYSTEM_PROMPT) tr(R.string.common_default)
                                        else tr(R.string.common_customized),
                                        onClick = { systemPromptExpanded = !systemPromptExpanded },
                                        trailing = {
                                            TextButton(onClick = { systemPromptExpanded = !systemPromptExpanded }) {
                                                Text(if (systemPromptExpanded) tr(R.string.common_collapse) else tr(R.string.common_edit))
                                            }
                                        }
                                    )
                                    if (systemPromptExpanded) {
                                        OmniTextField(
                                            value = llmState.systemPrompt,
                                            onValueChange = llmViewModel::setSystemPrompt,
                                            label = tr(R.string.settings_system_prompt),
                                            minLines = 3,
                                            maxLines = 6,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = llmViewModel::resetSystemPrompt) {
                                                Text(tr(R.string.common_reset_default))
                                            }
                                        }
                                    }

                                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)

                                    Text(tr(R.string.settings_thermal_profile), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    val profileLabels = mapOf(
                                        ThermalPowerProfile.Eco to tr(R.string.settings_thermal_eco),
                                        ThermalPowerProfile.Balanced to tr(R.string.settings_thermal_balanced),
                                        ThermalPowerProfile.Performance to tr(R.string.settings_thermal_performance)
                                    )
                                    OmniTabs(
                                        items = ThermalPowerProfile.entries,
                                        selected = llmState.thermalProfile,
                                        label = { profileLabels[it] ?: it.name },
                                        onSelect = llmViewModel::setThermalProfile
                                    )
                                    val thermalDetailRes = when (llmState.thermalProfile) {
                                        ThermalPowerProfile.Eco -> R.string.settings_thermal_eco_desc
                                        ThermalPowerProfile.Balanced -> R.string.settings_thermal_balanced_desc
                                        ThermalPowerProfile.Performance -> R.string.settings_thermal_performance_desc
                                    }
                                    Text(tr(thermalDetailRes), color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)

                                    SettingRow(
                                        title = tr(R.string.settings_chat_threads),
                                        detail = tr(R.string.settings_chat_threads_detail, llmState.threads)
                                    )
                                    Slider(
                                        value = llmState.threads.toFloat(),
                                        onValueChange = { llmViewModel.setThreads(it.roundToInt()) },
                                        valueRange = 2f..8f,
                                        steps = 5
                                    )
                                    Text(tr(R.string.settings_kv_precision), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    OmniTabs(
                                        items = KvCachePrecision.entries,
                                        selected = llmState.ggufKvPrecision,
                                        label = { it.label },
                                        onSelect = llmViewModel::setGgufKvPrecision
                                    )
                                    Text(llmState.ggufKvPrecision.localizedDetail(), color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)

                                    SettingRow(
                                        title = tr(R.string.settings_mnn_opencl),
                                        detail = tr(R.string.settings_mnn_opencl_detail),
                                        onClick = { llmViewModel.setMnnUseOpenCl(!llmState.mnnUseOpenCl) },
                                        trailing = { Switch(checked = llmState.mnnUseOpenCl, onCheckedChange = null) }
                                    )
                                    Text(tr(R.string.settings_gguf_backend), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    val caps = llmState.ggufCapabilities
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(GgufComputeBackend.entries) { backend ->
                                            val label = when (backend) {
                                                GgufComputeBackend.Auto -> tr(R.string.settings_gguf_backend_auto)
                                                GgufComputeBackend.Cpu -> tr(R.string.settings_gguf_backend_cpu)
                                                GgufComputeBackend.Vulkan -> tr(R.string.settings_gguf_backend_vulkan)
                                                GgufComputeBackend.OpenCl -> tr(R.string.settings_gguf_backend_opencl)
                                                GgufComputeBackend.HexagonNpu -> tr(R.string.settings_gguf_backend_hexagon)
                                            }
                                            val available = caps.isSelectable(backend)
                                            ComposerChip(
                                                label = label,
                                                onClick = { if (available) llmViewModel.setGgufBackend(backend) },
                                                selected = llmState.ggufBackend == backend,
                                                enabled = available
                                            )
                                        }
                                    }
                                    val backendDetail = buildList {
                                        if (caps.hasKleidiAi) add(tr(R.string.settings_gguf_backend_kleidiai_on))
                                        when (llmState.ggufBackend) {
                                            GgufComputeBackend.Auto, GgufComputeBackend.Cpu ->
                                                add(tr(R.string.settings_gguf_backend_detail_auto))
                                            GgufComputeBackend.Vulkan ->
                                                add(tr(R.string.settings_gguf_backend_detail_gpu, caps.vulkanName.ifBlank { "Vulkan GPU" }))
                                            GgufComputeBackend.OpenCl ->
                                                add(tr(R.string.settings_gguf_backend_detail_gpu, caps.openClName.ifBlank { "OpenCL GPU" }))
                                            GgufComputeBackend.HexagonNpu ->
                                                add(tr(R.string.settings_gguf_backend_hexagon_ready))
                                        }
                                        if (!caps.hasHexagonRuntime && caps.hasHexagonFastRpc) {
                                            add(tr(R.string.settings_gguf_backend_hexagon_fastrpc))
                                        }
                                        if (llmState.loadedBackendLabel.isNotBlank()) {
                                            add(tr(R.string.settings_gguf_backend_active, llmState.loadedBackendLabel))
                                        }
                                    }.joinToString(" · ")
                                    Text(backendDetail, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        SettingsTab.Decision -> {
                            item(key = "decision-settings") {
                                SectionLabel(tr(R.string.settings_decision_inference))
                                SettingRow(
                                    title = tr(R.string.settings_decision_threads),
                                    detail = tr(R.string.settings_decision_threads_detail, decisionState.threads)
                                )
                                Slider(
                                    value = decisionState.threads.toFloat(),
                                    onValueChange = { decisionViewModel.setThreads(it.roundToInt()) },
                                    valueRange = 2f..8f,
                                    steps = 5
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    tr(R.string.settings_decision_note),
                                    color = OmniColors.Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        SettingsTab.Image -> {
                            item(key = "image-settings") {
                                SectionLabel(tr(R.string.settings_image_gen))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingRow(
                                        title = tr(R.string.settings_image_threads),
                                        detail = tr(R.string.settings_image_threads_detail, state.cpuThreads)
                                    )
                                    Slider(
                                        value = state.cpuThreads.toFloat(),
                                        onValueChange = { viewModel.setCpuThreads(it.roundToInt()) },
                                        valueRange = 2f..8f,
                                        steps = 5
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_keep_qwen_loaded),
                                        onClick = { viewModel.setKeepModelsLoaded(!state.keepModelsLoaded) },
                                        trailing = { Switch(checked = state.keepModelsLoaded, onCheckedChange = null) }
                                    )
                                    Text(tr(R.string.settings_sd_sampler), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(SUPPORTED_SD_SAMPLERS) { sampler ->
                                            ComposerChip(
                                                label = samplerDisplayLabel(sampler),
                                                onClick = { viewModel.updateCustomSdSettings { it.copy(sampler = sampler) } },
                                                selected = state.customSdSettings.sampler == sampler
                                            )
                                        }
                                    }
                                    SettingRow(
                                        title = tr(R.string.settings_sd_opencl),
                                        detail = tr(R.string.settings_sd_opencl_detail),
                                        onClick = { viewModel.updateCustomSdSettings { it.copy(useOpenCl = !it.useOpenCl) } },
                                        trailing = { Switch(checked = state.customSdSettings.useOpenCl, onCheckedChange = null) }
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_sd_vae_tiling),
                                        detail = tr(R.string.settings_sd_vae_tiling_detail),
                                        onClick = { viewModel.updateCustomSdSettings { it.copy(vaeTiling = !it.vaeTiling) } },
                                        trailing = { Switch(checked = state.customSdSettings.vaeTiling, onCheckedChange = null) }
                                    )
                                }
                            }
                        }

                        SettingsTab.Video -> {
                            item(key = "video-settings") {
                                val v = state.videoSettings
                                val isMiniMaxH3 = state.customVideoModels.firstOrNull { it.id == v.modelId }?.metadata?.get("arch") == "minimax_h3"
                                val resPresets = if (isMiniMaxH3) MiniMaxH3ResolutionPresets else VideoResolutionPresets
                                val framePresets = if (isMiniMaxH3) MiniMaxH3FramePresets else VideoFramePresets
                                SectionLabel(tr(R.string.settings_video_gen))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(tr(R.string.settings_video_res), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(resPresets) { preset ->
                                            ComposerChip(
                                                label = tr(preset.labelRes),
                                                onClick = { viewModel.setVideoResolution(preset.width, preset.height) },
                                                selected = v.width == preset.width && v.height == preset.height
                                            )
                                        }
                                    }
                                    Text(tr(R.string.settings_video_frames), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    OmniTabs(
                                        items = framePresets,
                                        selected = v.numFrames,
                                        label = { localized.getString(R.string.common_frames_value, it) },
                                        onSelect = viewModel::setVideoNumFrames
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_video_steps),
                                        detail = tr(R.string.settings_video_steps_detail, v.steps)
                                    )
                                    Slider(
                                        value = v.steps.toFloat(),
                                        onValueChange = { viewModel.setVideoSteps(it.roundToInt()) },
                                        valueRange = 1f..12f,
                                        steps = 10
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_video_shift),
                                        detail = String.format(Locale.US, "%.1f", v.shift)
                                    )
                                    Slider(
                                        value = v.shift.coerceIn(1.0f, 14.0f),
                                        onValueChange = { viewModel.setVideoShift((it * 2f).roundToInt() / 2f) },
                                        valueRange = 1.0f..14.0f
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_video_threads),
                                        detail = tr(R.string.settings_video_threads_detail, v.threads)
                                    )
                                    Slider(
                                        value = v.threads.toFloat(),
                                        onValueChange = { viewModel.setVideoThreads(it.roundToInt()) },
                                        valueRange = 2f..8f,
                                        steps = 5
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_video_opencl),
                                        detail = tr(R.string.settings_video_opencl_detail),
                                        onClick = { viewModel.setVideoUseOpenCl(!v.useOpenCl) },
                                        trailing = { Switch(checked = v.useOpenCl, onCheckedChange = null) }
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_video_vae_tiling),
                                        detail = tr(R.string.settings_video_vae_tiling_detail),
                                        onClick = { viewModel.setVideoVaeTiling(!v.vaeTiling) },
                                        trailing = { Switch(checked = v.vaeTiling, onCheckedChange = null) }
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_video_disk_stream),
                                        detail = tr(R.string.settings_video_disk_stream_detail),
                                        onClick = { viewModel.setVideoDiskStreaming(!v.diskStreaming) },
                                        trailing = { Switch(checked = v.diskStreaming, onCheckedChange = null) }
                                    )
                                }
                            }
                        }

                        SettingsTab.Music -> {
                            item(key = "music-settings") {
                                val m = state.musicSettings
                                SectionLabel(tr(R.string.settings_music_gen))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingRow(
                                        title = tr(R.string.settings_music_vulkan),
                                        detail = tr(R.string.settings_music_vulkan_detail),
                                        onClick = { viewModel.setMusicUseVulkan(!m.useVulkan) },
                                        trailing = { Switch(checked = m.useVulkan, onCheckedChange = null) }
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_music_threads),
                                        detail = tr(R.string.settings_music_threads_detail, m.threads)
                                    )
                                    Slider(
                                        value = m.threads.toFloat(),
                                        onValueChange = { viewModel.setMusicThreads(it.roundToInt()) },
                                        valueRange = 2f..8f,
                                        steps = 5
                                    )
                                    Text(tr(R.string.settings_music_duration), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    OmniTabs(
                                        items = MusicDurationPresets,
                                        selected = m.targetDurationSec,
                                        label = { localized.getString(R.string.common_seconds_value, it) },
                                        onSelect = viewModel::setMusicTargetDuration
                                    )
                                    SettingRow(
                                        title = tr(R.string.settings_music_ode_steps),
                                        detail = tr(R.string.common_steps_value, m.odeSteps)
                                    )
                                    Slider(
                                        value = m.odeSteps.toFloat(),
                                        onValueChange = { viewModel.setMusicOdeSteps(it.roundToInt()) },
                                        valueRange = 4f..32f
                                    )
                                    Text(tr(R.string.settings_music_cot), color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                                    val cotLabels = mapOf(
                                        CoTMode.Melody to CoTMode.Melody.localizedLabel(),
                                        CoTMode.Full to CoTMode.Full.localizedLabel(),
                                        CoTMode.Off to CoTMode.Off.localizedLabel()
                                    )
                                    OmniTabs(
                                        items = CoTMode.entries,
                                        selected = m.cotMode,
                                        label = { cotLabels[it] ?: it.name },
                                        onSelect = viewModel::setMusicCotMode
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMirrorSheet) {
        MirrorSourceSheet(hubState, hubViewModel) { showMirrorSheet = false }
    }

    if (showLanguageSheet) {
        LanguagePickerSheet(
            selected = appLanguage,
            onSelect = { lang ->
                AppLanguageManager.setLanguage(context, lang)
                showLanguageSheet = false
            },
            onDismiss = { showLanguageSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OmniColors.Raised
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = tr(R.string.settings_language_picker_title),
                color = OmniColors.Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item(key = AppLanguage.System.tag) {
                    LanguageOptionRow(
                        label = AppLanguage.System.displayLabel(),
                        selected = selected == AppLanguage.System,
                        onClick = { onSelect(AppLanguage.System) }
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = OmniColors.Line,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                items(AppLanguage.EXPLICIT_LANGUAGES, key = { it.tag }) { lang ->
                    LanguageOptionRow(
                        label = lang.nativeName,
                        selected = selected == lang,
                        onClick = { onSelect(lang) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) OmniColors.AccentSoft else androidx.compose.ui.graphics.Color.Transparent,
        border = if (selected) BorderStroke(0.5.dp, OmniColors.Accent.copy(alpha = 0.35f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = if (selected) OmniColors.Accent else OmniColors.Ink,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = tr(R.string.common_current),
                    tint = OmniColors.Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

internal fun samplerDisplayLabel(sampler: String): String = when (sampler) {
    "euler" -> "Euler"
    "euler_a" -> "Euler a"
    "dpm++2m" -> "DPM++ 2M"
    "lcm" -> "LCM"
    "heun" -> "Heun"
    else -> sampler
}

private fun formatTokenCount(tokens: Int): String =
    if (tokens >= 1024 && tokens % 1024 == 0) "${tokens / 1024}K" else tokens.toString()
