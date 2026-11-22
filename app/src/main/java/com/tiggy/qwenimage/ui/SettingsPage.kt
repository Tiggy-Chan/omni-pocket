package com.tiggy.qwenimage.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.ModelHubViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.LlmConversation
import com.tiggy.qwenimage.data.ThemeMode
import com.tiggy.qwenimage.engine.KvCachePrecision
import com.tiggy.qwenimage.engine.SUPPORTED_SD_SAMPLERS
import java.util.Locale
import kotlin.math.roundToInt

private val ContextWindowPresets = listOf(2048, 4096, 8192, 16384, 32768, 65536)
private val MaxNewTokenPresets = listOf(512, 1024, 2048, 4096, 8192)
private val VideoFramePresets = listOf(9, 17, 25, 33)
private val MusicDurationPresets = listOf(15, 30, 60, 90)

private data class VideoResolutionPreset(val width: Int, val height: Int, val label: String)

private val VideoResolutionPresets = listOf(
    VideoResolutionPreset(480, 832, "480×832 竖屏"),
    VideoResolutionPreset(832, 480, "832×480 横屏"),
    VideoResolutionPreset(480, 480, "480×480 方形"),
    VideoResolutionPreset(384, 640, "384×640 预览")
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
    val focusManager = LocalFocusManager.current
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val decisionState by decisionViewModel.state.collectAsStateWithLifecycle()
    val hubState by hubViewModel.state.collectAsStateWithLifecycle()
    var showMirrorSheet by remember { mutableStateOf(false) }

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

    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "未知" }

    val applyCustomContext = {
        val parsed = contextInput.trim().toIntOrNull()
        if (parsed == null || parsed !in LlmViewModel.MIN_CONTEXT_TOKENS..LlmViewModel.MAX_CONTEXT_TOKENS) {
            contextInputError = "请输入 ${LlmViewModel.MIN_CONTEXT_TOKENS} 到 ${LlmViewModel.MAX_CONTEXT_TOKENS} 之间的整数"
        } else {
            contextInputError = null
            llmViewModel.setContextTokens(parsed)
            focusManager.clearFocus()
        }
    }

    val applyCustomMaxOut = {
        val parsed = maxOutInput.trim().toIntOrNull()
        if (parsed == null || parsed !in LlmViewModel.MIN_MAX_NEW_TOKENS..LlmViewModel.MAX_MAX_NEW_TOKENS) {
            maxOutInputError = "请输入 ${LlmViewModel.MIN_MAX_NEW_TOKENS} 到 ${LlmViewModel.MAX_MAX_NEW_TOKENS} 之间的整数"
        } else {
            maxOutInputError = null
            llmViewModel.setMaxNewTokens(parsed)
            focusManager.clearFocus()
        }
    }

    WorkspaceScaffold(title = "设置", onMenu = onMenu) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp
                )
            ) {
                item(key = "appearance") {
                    SectionLabel("外观")
                    Text(
                        "主题",
                        color = OmniColors.Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    OmniTabs(
                        items = ThemeMode.entries,
                        selected = state.themeMode,
                        label = { it.label },
                        onSelect = viewModel::setThemeMode
                    )
                }

                item(key = "hardware-source") {
                    SectionLabel("设备与模型源", Modifier.padding(top = 18.dp))
                    hubState.deviceHardware?.let { hw ->
                        val socSuffix = if (hw.socLabel.isNotBlank()) " · ${hw.socLabel}" else ""
                        SettingRow(title = "本机硬件", detail = "${hw.summaryLine()}$socSuffix")
                        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                    }
                    SettingRow(
                        title = "模型下载镜像",
                        detail = "${hubState.hubSettings.displayName} · ${hubState.hubSettings.effectiveBaseUrl}",
                        onClick = { showMirrorSheet = true },
                        trailing = { TextButton(onClick = { showMirrorSheet = true }) { Text("修改") } }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                }

                item(key = "chat-inference") {
                    SectionLabel("对话推理", Modifier.padding(top = 18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("最大上下文窗口", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
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
                                label = "自定义上下文上限 (512–131072)",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { applyCustomContext() }),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = applyCustomContext,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("应用") }
                        }
                        contextInputError?.let {
                            Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.labelSmall)
                        }
                        val trainHint = buildList {
                            add("当前已设 ${llmState.contextTokens} tokens")
                            if (llmState.loadedTrainContext > 0) {
                                add("已加载模型训练窗口 ${llmState.loadedTrainContext}（生效 ${llmState.loadedEffectiveContext}）")
                            }
                            add("MNN 上限 65536；长窗口建议搭配 Q8_0 / Q4_0 KV 缓存")
                        }.joinToString(" · ")
                        Text(trainHint, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)

                        Spacer(Modifier.height(2.dp))
                        Text("单次最大输出", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
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
                                label = "自定义最大输出 (128–32768)",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { applyCustomMaxOut() }),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = applyCustomMaxOut,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("应用") }
                        }
                        maxOutInputError?.let {
                            Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.labelSmall)
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 4.dp))

                        SettingRow(
                            title = "温度（Temperature）",
                            detail = if (llmState.temperature <= 0.01f) "0.00 · 贪心确定性输出"
                            else String.format(Locale.US, "%.2f", llmState.temperature)
                        )
                        Slider(
                            value = llmState.temperature,
                            onValueChange = { llmViewModel.setTemperature((it * 20).roundToInt() / 20f) },
                            valueRange = 0f..1.5f
                        )
                        SettingRow(
                            title = "核采样（Top-P）",
                            detail = String.format(Locale.US, "%.2f", llmState.topP)
                        )
                        Slider(
                            value = llmState.topP,
                            onValueChange = { llmViewModel.setTopP((it * 20).roundToInt() / 20f) },
                            valueRange = 0.1f..1.0f
                        )
                        SettingRow(
                            title = "重复惩罚（Repeat Penalty）",
                            detail = String.format(Locale.US, "%.2f", llmState.repeatPenalty)
                        )
                        Slider(
                            value = llmState.repeatPenalty,
                            onValueChange = { llmViewModel.setRepeatPenalty((it * 20).roundToInt() / 20f) },
                            valueRange = 1.0f..1.4f
                        )

                        SettingRow(
                            title = "系统提示词",
                            detail = if (llmState.systemPrompt == LlmConversation.SYSTEM_PROMPT) "默认" else "已自定义",
                            onClick = { systemPromptExpanded = !systemPromptExpanded },
                            trailing = {
                                TextButton(onClick = { systemPromptExpanded = !systemPromptExpanded }) {
                                    Text(if (systemPromptExpanded) "收起" else "编辑")
                                }
                            }
                        )
                        if (systemPromptExpanded) {
                            OmniTextField(
                                value = llmState.systemPrompt,
                                onValueChange = llmViewModel::setSystemPrompt,
                                label = "System Prompt",
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = llmViewModel::resetSystemPrompt) { Text("恢复默认") }
                            }
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)

                        SettingRow(title = "对话 CPU 线程", detail = "本地对话使用 ${llmState.threads} 个线程")
                        Slider(
                            value = llmState.threads.toFloat(),
                            onValueChange = { llmViewModel.setThreads(it.roundToInt()) },
                            valueRange = 2f..8f,
                            steps = 5
                        )
                        Text("GGUF KV 缓存精度", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                        OmniTabs(
                            items = KvCachePrecision.entries,
                            selected = llmState.ggufKvPrecision,
                            label = { it.label },
                            onSelect = llmViewModel::setGgufKvPrecision
                        )
                        Text(llmState.ggufKvPrecision.detail, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)

                        SettingRow(
                            title = "MNN OpenCL GPU 加速",
                            detail = "部分设备支持；初始化失败时自动回退 CPU",
                            onClick = { llmViewModel.setMnnUseOpenCl(!llmState.mnnUseOpenCl) },
                            trailing = { Switch(checked = llmState.mnnUseOpenCl, onCheckedChange = null) }
                        )
                        SettingRow(
                            title = "GGUF OpenCL GPU 卸载",
                            detail = if (llmState.ggufGpuAvailable) {
                                val gpuTag = llmState.ggufGpuDeviceName.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                                "将 GGUF 模型层卸载到手机 OpenCL GPU$gpuTag；建议搭配 FP16 KV，异常时自动回退 CPU"
                            } else "当前设备未检测到可用 OpenCL GPU，自动使用 CPU",
                            onClick = if (llmState.ggufGpuAvailable) ({ llmViewModel.setGgufUseVulkanGpu(!llmState.ggufUseVulkanGpu) }) else null,
                            trailing = {
                                Switch(
                                    checked = llmState.ggufUseVulkanGpu && llmState.ggufGpuAvailable,
                                    onCheckedChange = null,
                                    enabled = llmState.ggufGpuAvailable
                                )
                            }
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 8.dp))
                }

                item(key = "decision-settings") {
                    SectionLabel("决策推理", Modifier.padding(top = 18.dp))
                    SettingRow(title = "Elephant-1 CPU 线程", detail = "结构化决策推理使用 ${decisionState.threads} 个线程")
                    Slider(
                        value = decisionState.threads.toFloat(),
                        onValueChange = { decisionViewModel.setThreads(it.roundToInt()) },
                        valueRange = 2f..8f,
                        steps = 5
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 8.dp))
                }

                item(key = "image-settings") {
                    SectionLabel("图像生成", Modifier.padding(top = 18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingRow(title = "图像 CPU 线程", detail = "Qwen / MOSS / 通用扩散使用 ${state.cpuThreads} 个线程")
                        Slider(
                            value = state.cpuThreads.toFloat(),
                            onValueChange = { viewModel.setCpuThreads(it.roundToInt()) },
                            valueRange = 2f..8f,
                            steps = 5
                        )
                        SettingRow(
                            title = "空闲时保持 Qwen 模型常驻",
                            onClick = { viewModel.setKeepModelsLoaded(!state.keepModelsLoaded) },
                            trailing = { Switch(checked = state.keepModelsLoaded, onCheckedChange = null) }
                        )
                        Text("通用扩散默认采样器", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
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
                            title = "通用扩散默认 OpenCL GPU",
                            detail = "DiT/UNet 在 GPU 执行，文本编码与 VAE 保持 CPU",
                            onClick = { viewModel.updateCustomSdSettings { it.copy(useOpenCl = !it.useOpenCl) } },
                            trailing = { Switch(checked = state.customSdSettings.useOpenCl, onCheckedChange = null) }
                        )
                        SettingRow(
                            title = "通用扩散默认 VAE 分块解码",
                            detail = "降低 768×768 及以上分辨率峰值内存",
                            onClick = { viewModel.updateCustomSdSettings { it.copy(vaeTiling = !it.vaeTiling) } },
                            trailing = { Switch(checked = state.customSdSettings.vaeTiling, onCheckedChange = null) }
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 8.dp))
                }

                item(key = "video-settings") {
                    val v = state.videoSettings
                    SectionLabel("视频生成", Modifier.padding(top = 18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("默认分辨率", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(VideoResolutionPresets) { preset ->
                                ComposerChip(
                                    label = preset.label,
                                    onClick = { viewModel.setVideoResolution(preset.width, preset.height) },
                                    selected = v.width == preset.width && v.height == preset.height
                                )
                            }
                        }
                        Text("默认帧数", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                        OmniTabs(
                            items = VideoFramePresets,
                            selected = v.numFrames,
                            label = { "$it 帧" },
                            onSelect = viewModel::setVideoNumFrames
                        )
                        SettingRow(title = "默认采样步数", detail = "${v.steps} 步（Turbo 建议 4 步，标准版建议 8–12 步）")
                        Slider(
                            value = v.steps.toFloat(),
                            onValueChange = { viewModel.setVideoSteps(it.roundToInt()) },
                            valueRange = 1f..12f,
                            steps = 10
                        )
                        SettingRow(
                            title = "默认流偏移（Flow Shift）",
                            detail = String.format(Locale.US, "%.1f", v.shift)
                        )
                        Slider(
                            value = v.shift,
                            onValueChange = { viewModel.setVideoShift((it * 2f).roundToInt() / 2f) },
                            valueRange = 1.0f..10.0f
                        )
                        SettingRow(title = "视频文本编码 CPU 线程", detail = "UMT5-XXL 编码使用 ${v.threads} 个线程")
                        Slider(
                            value = v.threads.toFloat(),
                            onValueChange = { viewModel.setVideoThreads(it.roundToInt()) },
                            valueRange = 2f..8f,
                            steps = 5
                        )
                        SettingRow(
                            title = "视频 DiT OpenCL GPU 加速",
                            detail = "VAE 始终固定在 CPU 运行以避免花屏",
                            onClick = { viewModel.setVideoUseOpenCl(!v.useOpenCl) },
                            trailing = { Switch(checked = v.useOpenCl, onCheckedChange = null) }
                        )
                        SettingRow(
                            title = "视频 VAE 分块解码",
                            detail = "高帧数或内存吃紧时建议开启",
                            onClick = { viewModel.setVideoVaeTiling(!v.vaeTiling) },
                            trailing = { Switch(checked = v.vaeTiling, onCheckedChange = null) }
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 8.dp))
                }

                item(key = "music-settings") {
                    val m = state.musicSettings
                    SectionLabel("音乐生成", Modifier.padding(top = 18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingRow(
                            title = "音乐 Vulkan GPU 加速",
                            detail = "支持 YuE2、ACE-Step 1.5、HeartMuLa 与 Stable Audio 3",
                            onClick = { viewModel.setMusicUseVulkan(!m.useVulkan) },
                            trailing = { Switch(checked = m.useVulkan, onCheckedChange = null) }
                        )
                        SettingRow(title = "音乐 CPU 线程", detail = "本地音频推理使用 ${m.threads} 个线程")
                        Slider(
                            value = m.threads.toFloat(),
                            onValueChange = { viewModel.setMusicThreads(it.roundToInt()) },
                            valueRange = 2f..8f,
                            steps = 5
                        )
                        Text("默认目标时长", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                        OmniTabs(
                            items = MusicDurationPresets,
                            selected = m.targetDurationSec,
                            label = { "$it 秒" },
                            onSelect = viewModel::setMusicTargetDuration
                        )
                        SettingRow(title = "默认 ODE 步数", detail = "${m.odeSteps} 步")
                        Slider(
                            value = m.odeSteps.toFloat(),
                            onValueChange = { viewModel.setMusicOdeSteps(it.roundToInt()) },
                            valueRange = 4f..32f
                        )
                        Text("默认 CoT 规划模式", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                        OmniTabs(
                            items = CoTMode.entries,
                            selected = m.cotMode,
                            label = { it.label },
                            onSelect = viewModel::setMusicCotMode
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 8.dp))
                }

                item(key = "about") {
                    SectionLabel("关于", Modifier.padding(top = 18.dp))
                    SettingRow(title = "版本", detail = version)
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                    Text(
                        "对话（MNN / llama.cpp GGUF）、决策（Elephant-1）、图像（Qwen / MOSS / SD / Z-Image / FLUX.2 Klein）、音乐（YuE2 / ACE-Step / HeartMuLa / Stable Audio 3）与视频（Wan2.2 / Wan2.1）全部在手机本机运行，推理无需联网。",
                        color = OmniColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }

    if (showMirrorSheet) {
        MirrorSourceSheet(hubState, hubViewModel) { showMirrorSheet = false }
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
