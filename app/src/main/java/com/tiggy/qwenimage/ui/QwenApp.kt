package com.tiggy.qwenimage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.ImageGenerationBackend
import com.tiggy.qwenimage.data.WorkspaceTab
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

@Composable
fun QwenApp(viewModel: QwenViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var management by remember { mutableStateOf<WorkspaceTab?>(null) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    val hazeState = remember { HazeState() }
    val busy = state.activeTask != null

    Box(Modifier.fillMaxSize().background(OmniColors.Background)) {
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            LiquidBackdrop(animated = !busy)
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(top = 68.dp, bottom = 84.dp)
            ) {
                if (management == null && state.domain == AppDomain.ImageStudio) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        OmniTabs(
                            items = listOf(WorkspaceTab.Generate, WorkspaceTab.Queue),
                            selected = state.tab,
                            accent = OmniColors.Violet,
                            label = { if (it == WorkspaceTab.Generate) "创作" else "队列" },
                            onSelect = viewModel::selectTab
                        )
                        if (state.tab == WorkspaceTab.Generate) {
                            Spacer(Modifier.height(8.dp))
                            ImageBackendSelector(state.imageBackend, viewModel::setImageBackend)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (management) {
                        WorkspaceTab.Models -> ModelsScreen(viewModel, state.modelStatus, state.repo, state.revision, state.modelTask)
                        WorkspaceTab.Settings -> SettingsScreen(
                            viewModel = viewModel,
                            threads = state.cpuThreads,
                            keepLoaded = state.keepModelsLoaded,
                            status = state.currentTask.status,
                            modelReady = state.modelStatus?.ready == true
                        )
                        else -> when (state.domain) {
                            AppDomain.ImageStudio -> when (state.tab) {
                                WorkspaceTab.Generate -> if (state.imageBackend == ImageGenerationBackend.MossWorkstation) {
                                    MossStudioScreen(state, viewModel)
                                } else {
                                    QwenGenerateScreen(state, viewModel) { previewImagePath = it }
                                }
                                WorkspaceTab.Queue -> ImageQueueScreen(state.jobs, viewModel::cancelJob, viewModel::reuseJob) { previewImagePath = it }
                                else -> Unit
                            }
                            AppDomain.MusicStudio -> MusicStudioScreen(state, viewModel)
                            AppDomain.VideoStudio -> VideoStudioScreen(state, viewModel)
                        }
                    }
                }
            }
        }

        OmniTopBar(
            state = state,
            management = management,
            hazeState = hazeState,
            onBack = { management = null },
            onModels = { management = WorkspaceTab.Models },
            onSettings = { management = WorkspaceTab.Settings }
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val accent = when (state.domain) {
                AppDomain.ImageStudio -> OmniColors.Violet
                AppDomain.MusicStudio -> OmniColors.Mint
                AppDomain.VideoStudio -> OmniColors.Amber
            }
            OverlayGlass(hazeState, accent = accent, blurEnabled = !busy, radius = 26.dp) {
                OmniTabs(
                    items = AppDomain.entries,
                    selected = state.domain,
                    accent = accent,
                    label = {
                        when (it) {
                            AppDomain.ImageStudio -> "图像"
                            AppDomain.MusicStudio -> "音乐"
                            AppDomain.VideoStudio -> "视频"
                        }
                    },
                    onSelect = {
                        management = null
                        viewModel.setDomain(it)
                    }
                )
            }
        }
    }

    state.alert?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("需要处理") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } }
        )
    }

    previewImagePath?.let { path ->
        Dialog(onDismissRequest = { previewImagePath = null }) {
            Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(OmniColors.ContentStrong).padding(12.dp)) {
                Column {
                    OmniAsyncImage(path, "作品大图", Modifier.fillMaxWidth(), maxDimension = 2048)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(path.substringAfterLast('/'), color = OmniColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { previewImagePath = null }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Close, "关闭预览", tint = OmniColors.Ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OmniTopBar(
    state: QwenUiState,
    management: WorkspaceTab?,
    hazeState: HazeState,
    onBack: () -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        OverlayGlass(hazeState, blurEnabled = state.activeTask == null, radius = 22.dp) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (management != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = OmniColors.Ink)
                    }
                } else {
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        management?.let { if (it == WorkspaceTab.Models) "模型" else "设置" } ?: "Omni Pocket",
                        color = OmniColors.Ink,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (management == null) {
                        Text(
                            when (state.domain) {
                                AppDomain.ImageStudio -> if (state.imageBackend == ImageGenerationBackend.MossWorkstation) "MOSS · 工作站" else "Qwen · 本机"
                                AppDomain.MusicStudio -> "YuE2 · 工作站"
                                AppDomain.VideoStudio -> "视频创作"
                            },
                            color = OmniColors.Muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (management == null) {
                    IconButton(onClick = onModels, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Memory, "模型", tint = OmniColors.Ink)
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Settings, "设置", tint = OmniColors.Ink)
                    }
                }
            }
        }
    }
}
