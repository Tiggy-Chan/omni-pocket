package com.tiggy.qwenimage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.ModelHubViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.ThemeMode

private val MaxNewTokenChoices = listOf(512, 1024, 2048)
private val ContextTokenChoices = listOf(2048, 4096, 8192)

@Composable
fun SettingsPage(
    state: QwenUiState,
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    hubViewModel: ModelHubViewModel,
    onMenu: (() -> Unit)?
) {
    val context = LocalContext.current
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val hubState by hubViewModel.state.collectAsStateWithLifecycle()
    var showMirrorSheet by remember { mutableStateOf(false) }

    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "未知" }

    WorkspaceScaffold(title = "设置", onMenu = onMenu) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp
                )
            ) {
                item {
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
                item {
                    SectionLabel("模型源", Modifier.padding(top = 16.dp))
                    SettingRow(
                        title = "模型下载镜像",
                        detail = "${hubState.hubSettings.displayName} · ${hubState.hubSettings.effectiveBaseUrl}",
                        onClick = { showMirrorSheet = true },
                        trailing = { TextButton(onClick = { showMirrorSheet = true }) { Text("修改") } }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                }
                item {
                    SectionLabel("对话推理", Modifier.padding(top = 16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingRow(title = "对话 CPU 线程", detail = "本地对话使用 ${llmState.threads} 个线程")
                        Slider(
                            value = llmState.threads.toFloat(),
                            onValueChange = { llmViewModel.setThreads(it.toInt()) },
                            valueRange = 2f..8f,
                            steps = 5
                        )
                        Text("单次最大输出", color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                        OmniTabs(
                            items = MaxNewTokenChoices,
                            selected = llmState.maxNewTokens,
                            label = { "$it tokens" },
                            onSelect = llmViewModel::setMaxNewTokens
                        )
                        Text(
                            "上下文长度",
                            color = OmniColors.Ink,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        OmniTabs(
                            items = ContextTokenChoices,
                            selected = llmState.contextTokens,
                            label = { "$it" },
                            onSelect = llmViewModel::setContextTokens
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 12.dp))
                }
                item {
                    SectionLabel("图像与媒体性能", Modifier.padding(top = 16.dp))
                    SettingRow(title = "CPU 线程", detail = "图像生成使用 ${state.cpuThreads} 个线程")
                    Slider(
                        value = state.cpuThreads.toFloat(),
                        onValueChange = { viewModel.setCpuThreads(it.toInt()) },
                        valueRange = 2f..8f,
                        steps = 5
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                    SettingRow(
                        title = "空闲时保持图像模型常驻",
                        onClick = { viewModel.setKeepModelsLoaded(!state.keepModelsLoaded) },
                        trailing = {
                            Switch(checked = state.keepModelsLoaded, onCheckedChange = null)
                        }
                    )
                }
                item {
                    SectionLabel("关于", Modifier.padding(top = 16.dp))
                    SettingRow(title = "版本", detail = version)
                    HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                    Text(
                        "对话（MNN / llama.cpp GGUF）、决策（Elephant-1）、图像（Qwen / MOSS / 通用扩散）、音乐（YuE2）和视频（Wan2.2）全部在本机运行，推理不需要网络。",
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
