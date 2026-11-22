package com.tiggy.qwenimage.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionPhase
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.ModelHubUiState
import com.tiggy.qwenimage.ModelHubViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.ActiveDownloadState
import com.tiggy.qwenimage.data.GenerationBackend
import com.tiggy.qwenimage.data.HfRepoOption
import com.tiggy.qwenimage.data.HfRepoSummary
import com.tiggy.qwenimage.data.HubMirrorMode
import com.tiggy.qwenimage.data.HubSearchCategory
import com.tiggy.qwenimage.data.InstalledHubModel
import com.tiggy.qwenimage.data.LlmModelStatus
import com.tiggy.qwenimage.data.formatBytesCompact
import java.util.Locale

private enum class ModelsPageTab(val label: String) {
    Installed("本地模型"),
    Discover("发现模型")
}

private enum class BuiltInDeleteTarget(val label: String) {
    Decision("Elephant-1 9B"),
    Qwen("Qwen 图像模型"),
    Moss("MOSS 本机模型"),
    Yue2("YuE2-3B 音乐模型"),
    Wan("Wan2.2 视频模型")
}

/** Unified model management and Hugging Face / hf-mirror discovery page. */
@Composable
fun ModelsPage(
    state: QwenUiState,
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    decisionViewModel: DecisionViewModel,
    hubViewModel: ModelHubViewModel,
    onMenu: (() -> Unit)?,
    onUseHubModel: (InstalledHubModel) -> Unit
) {
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val decisionState by decisionViewModel.state.collectAsStateWithLifecycle()
    val hubState by hubViewModel.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(ModelsPageTab.Installed) }
    var deleteChat by remember { mutableStateOf<LlmModelStatus?>(null) }
    var deleteHubModel by remember { mutableStateOf<InstalledHubModel?>(null) }
    var deleteBuiltIn by remember { mutableStateOf<BuiltInDeleteTarget?>(null) }
    var showMirrorSheet by remember { mutableStateOf(false) }
    var sourceOpen by rememberSaveable { mutableStateOf(false) }
    var elephantManualOpen by rememberSaveable { mutableStateOf(false) }

    val qwenFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importModels(uri)
    }
    val mossFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importMossModels(uri)
    }
    LaunchedEffect(Unit) {
        viewModel.refreshAllModelStatuses()
        llmViewModel.refreshModels()
        decisionViewModel.refreshModel()
        hubViewModel.refreshStorage()
    }

    val downloads = hubState.downloads
    val modelTask = state.modelTask
    val modelBusy = modelTask.running
    val mossTaskActive = state.activeTask?.backend == GenerationBackend.LocalMoss
    val showModelTask = modelTask.running || modelTask.error != null
    val qwenStatus = state.modelStatus
    val mossStatus = state.mossModelStatus
    val wanStatus = state.wanModelStatus
    val yue2Status = state.yue2ModelStatus

    WorkspaceScaffold(
        title = "模型",
        subtitle = "下载源：${hubState.hubSettings.displayName}",
        onMenu = onMenu,
        actions = {
            TextButton(onClick = { showMirrorSheet = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Language, null, tint = OmniColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("切换源", color = OmniColors.Accent)
            }
        }
    ) { padding ->
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
                item(key = "page-tabs") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OmniTabs(
                            items = ModelsPageTab.entries,
                            selected = tab,
                            label = { it.label },
                            onSelect = { tab = it }
                        )
                        if (hubState.storage.availableBytes > 0L) {
                            Text(
                                "模型已占 ${formatBytesCompact(hubState.storage.modelsUsedBytes)} · 手机剩余 ${formatBytesCompact(hubState.storage.availableBytes)}",
                                color = OmniColors.Dim,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }

                if (downloads.active != null || downloads.lastError != null) {
                    item(key = "active-download") {
                        Spacer(Modifier.height(10.dp))
                        DownloadStatusCard(
                            active = downloads.active,
                            queueSize = downloads.queue.size,
                            error = downloads.lastError,
                            onCancel = hubViewModel::cancelActiveDownload,
                            onDismissError = hubViewModel::clearDownloadError
                        )
                    }
                }

                when (tab) {
                    ModelsPageTab.Installed -> {
                        if (hubState.installedModels.isNotEmpty()) {
                            item(key = "hub-installed-label") { SectionLabel("已从社区下载", Modifier.padding(top = 12.dp)) }
                            items(hubState.installedModels, key = { "hub-${it.id}" }) { model ->
                                ModelRow(
                                    name = model.name,
                                    status = "${model.type.label} · ${formatBytesCompact(model.totalBytes)} · ${model.repo}",
                                    ready = true,
                                    trailing = {
                                        ModelButton("使用", { onUseHubModel(model) })
                                        ModelButton("删除", { deleteHubModel = model }, danger = true)
                                    }
                                )
                            }
                        }

                        item(key = "chat-label") { SectionLabel("对话 · 内置", Modifier.padding(top = 16.dp)) }
                        items(llmState.models, key = { "chat-${it.spec.id}" }) { status ->
                            val dlId = "builtin-llm:${status.spec.id}"
                            val queued = downloads.isPendingOrActive(dlId)
                            val complete = status.present == status.spec.artifacts.size
                            ModelRow(
                                name = status.spec.name,
                                status = if (status.ready) "已校验 · ${formatBytesCompact(status.spec.totalBytes)}"
                                else "${status.message} · ${formatBytesCompact(status.spec.totalBytes)}",
                                ready = status.ready,
                                trailing = {
                                    when {
                                        queued -> ModelButton("取消", { hubViewModel.cancelQueuedDownload(dlId) })
                                        status.ready -> ModelButton("删除", { deleteChat = status }, enabled = !llmState.busy, danger = true)
                                        complete -> ModelButton("校验", { llmViewModel.verifyModel(status.spec.id) }, enabled = !llmState.busy)
                                        else -> ModelButton("下载", { llmViewModel.downloadModel(status.spec.id) })
                                    }
                                }
                            )
                        }

                        item(key = "decision-label") { SectionLabel("决策 · 内置", Modifier.padding(top = 16.dp)) }
                        item(key = "decision") {
                            val model = decisionState.model
                            val dlId = "builtin-decision:elephant-1"
                            val queued = downloads.isPendingOrActive(dlId)
                            if (model == null) {
                                Text(
                                    "正在读取模型状态",
                                    color = OmniColors.Muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            } else {
                                val complete = model.present == model.spec.artifacts.size
                                ModelRow(
                                    name = model.spec.name,
                                    status = if (model.ready) "已校验 · ${formatBytesCompact(model.spec.totalBytes)}"
                                    else "${model.message} · ${formatBytesCompact(model.spec.totalBytes)}",
                                    ready = model.ready,
                                    trailing = {
                                        when {
                                            queued -> ModelButton("取消", { hubViewModel.cancelQueuedDownload(dlId) })
                                            model.ready -> ModelButton(
                                                "删除",
                                                { deleteBuiltIn = BuiltInDeleteTarget.Decision },
                                                enabled = !decisionState.busy,
                                                danger = true
                                            )
                                            complete -> ModelButton("校验", decisionViewModel::verifyModel, enabled = !decisionState.busy)
                                            else -> ModelButton("下载", decisionViewModel::downloadModel)
                                        }
                                    },
                                    below = {
                                        if (decisionState.busy) {
                                            Text(
                                                when (decisionState.phase) {
                                                    DecisionPhase.Verifying -> "正在校验"
                                                    DecisionPhase.Loading -> "正在加载"
                                                    DecisionPhase.Computing -> "正在推理"
                                                    DecisionPhase.Idle -> ""
                                                },
                                                color = OmniColors.Muted,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(bottom = 6.dp)
                                            )
                                        }
                                        decisionState.error?.let {
                                            Text(
                                                it,
                                                color = OmniColors.Danger,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(bottom = 6.dp)
                                            )
                                        }
                                        if (!model.ready) {
                                            TextButton(onClick = { elephantManualOpen = !elephantManualOpen }) {
                                                Text(
                                                    if (elephantManualOpen) "收起 USB 手动导入说明" else "也可通过电脑 USB 导入",
                                                    color = OmniColors.Dim,
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                            if (elephantManualOpen) {
                                                SelectionContainer(Modifier.padding(bottom = 8.dp)) {
                                                    Text(
                                                        "scripts/prepare_elephant_model.sh\nscripts/push_llm_models.sh elephant-1",
                                                        color = OmniColors.Ink,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                                            .background(OmniColors.Field).padding(12.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        item(key = "image-label") { SectionLabel("图像 · 内置", Modifier.padding(top = 16.dp)) }
                        item(key = "moss") {
                            val dlId = "builtin-image:moss"
                            val queued = downloads.isPendingOrActive(dlId)
                            ModelRow(
                                name = "MOSS 本机",
                                status = mossStatus?.message ?: "尚未找到模型",
                                ready = mossStatus?.ready == true,
                                trailing = {
                                    when {
                                        queued -> ModelButton("取消", { hubViewModel.cancelQueuedDownload(dlId) })
                                        mossStatus?.ready == true -> ModelButton(
                                            "删除",
                                            { deleteBuiltIn = BuiltInDeleteTarget.Moss },
                                            enabled = !modelBusy,
                                            danger = true
                                        )
                                        else -> ModelButton("下载", viewModel::downloadMossModels)
                                    }
                                },
                                below = {
                                    if (showModelTask && mossTaskActive) TaskLine(modelTask, modifier = Modifier.padding(bottom = 4.dp))
                                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ModelButton("导入目录", { mossFolderPicker.launch(null) }, enabled = !modelBusy, modifier = Modifier.weight(1f))
                                        ModelButton(
                                            "校验",
                                            viewModel::verifyMossModels,
                                            enabled = !modelBusy && mossStatus?.let { it.total > 0 && it.present == it.total } == true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            )
                        }
                        item(key = "qwen") {
                            val dlId = "builtin-image:qwen"
                            val queued = downloads.isPendingOrActive(dlId)
                            ModelRow(
                                name = "Qwen 图像",
                                status = qwenStatus?.message ?: "尚未找到模型",
                                ready = qwenStatus?.ready == true,
                                trailing = {
                                    if (qwenStatus?.ready == true) {
                                        ModelButton(
                                            "删除",
                                            { deleteBuiltIn = BuiltInDeleteTarget.Qwen },
                                            enabled = !modelBusy,
                                            danger = true
                                        )
                                    }
                                },
                                below = {
                                    if (showModelTask && !mossTaskActive) TaskLine(modelTask, modifier = Modifier.padding(bottom = 4.dp))
                                    if (qwenStatus?.ready == true) {
                                        ModelButton(
                                            "检查推理引擎",
                                            viewModel::checkRuntime,
                                            enabled = !modelBusy
                                        )
                                    }
                                    SettingRow(
                                        title = "来源仓库",
                                        detail = state.repo.ifBlank { "未设置仓库" },
                                        onClick = { sourceOpen = !sourceOpen },
                                        trailing = {
                                            Icon(
                                                if (sourceOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                if (sourceOpen) "收起来源" else "展开来源",
                                                tint = OmniColors.Dim
                                            )
                                        }
                                    )
                                    if (sourceOpen) {
                                        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            OmniTextField(
                                                value = state.repo,
                                                onValueChange = viewModel::setRepo,
                                                label = "Hugging Face owner/repo",
                                                placeholder = "包含 manifest.json 的转换仓库",
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            OmniTextField(
                                                value = state.revision,
                                                onValueChange = viewModel::setRevision,
                                                label = "Revision",
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (queued) {
                                                    ModelButton("取消下载", { hubViewModel.cancelQueuedDownload(dlId) }, modifier = Modifier.weight(1f))
                                                } else {
                                                    ModelButton(
                                                        "下载",
                                                        viewModel::downloadModels,
                                                        enabled = state.repo.isNotBlank(),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                                ModelButton(
                                                    "导入",
                                                    { qwenFolderPicker.launch(null) },
                                                    enabled = !modelBusy,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        item(key = "music-label") { SectionLabel("音乐 · 内置", Modifier.padding(top = 16.dp)) }
                        item(key = "yue2") {
                            val dlId = "builtin-music:yue2"
                            val queued = downloads.isPendingOrActive(dlId)
                            val ready = yue2Status?.ready == true
                            ModelRow(
                                name = "YuE2-3B 本机",
                                status = yue2Status?.let { "${it.message} · ${formatBytesCompact(it.totalBytes)}" } ?: "约 2.9 GB",
                                ready = ready,
                                trailing = {
                                    when {
                                        queued -> ModelButton("取消", { hubViewModel.cancelQueuedDownload(dlId) })
                                        ready -> {
                                            if (yue2Status?.verified != true) {
                                                ModelButton("校验", viewModel::verifyYue2Models, enabled = !modelBusy)
                                            }
                                            ModelButton("删除", { deleteBuiltIn = BuiltInDeleteTarget.Yue2 }, enabled = !modelBusy, danger = true)
                                        }
                                        else -> ModelButton("下载", viewModel::downloadYue2Models)
                                    }
                                },
                                below = {
                                    Text(
                                        "权重为 CC BY-NC 4.0，仅限非商业使用",
                                        color = OmniColors.Dim,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            )
                        }

                        item(key = "video-label") { SectionLabel("视频 · 内置", Modifier.padding(top = 16.dp)) }
                        item(key = "wan22") {
                            val dlId = "builtin-video:wan22"
                            val queued = downloads.isPendingOrActive(dlId)
                            val ready = wanStatus?.ready == true
                            ModelRow(
                                name = "Wan2.2 视频本机",
                                status = wanStatus?.let { "${it.message} · ${formatBytesCompact(it.totalBytes)}" } ?: "约 5.9 GB",
                                ready = ready,
                                trailing = {
                                    when {
                                        queued -> ModelButton("取消", { hubViewModel.cancelQueuedDownload(dlId) })
                                        ready -> {
                                            if (wanStatus?.verified != true) {
                                                ModelButton("校验", viewModel::verifyWanModels, enabled = !modelBusy)
                                            }
                                            ModelButton("删除", { deleteBuiltIn = BuiltInDeleteTarget.Wan }, enabled = !modelBusy, danger = true)
                                        }
                                        else -> ModelButton("下载", viewModel::downloadWanModels)
                                    }
                                },
                                below = {
                                    Text(
                                        "含 DiT、UMT5 文本编码器与 TAE 视频解码器（480×832 本机运行）",
                                        color = OmniColors.Dim,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            )
                        }
                    }

                    ModelsPageTab.Discover -> {
                        item(key = "hub-search-controls") {
                            Column(
                                Modifier.padding(top = 12.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OmniTabs(
                                    items = HubSearchCategory.entries,
                                    selected = hubState.category,
                                    label = { it.label },
                                    onSelect = hubViewModel::selectCategory
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OmniTextField(
                                        value = hubState.query,
                                        onValueChange = hubViewModel::updateQuery,
                                        label = "搜索仓库或输入 owner/repo",
                                        placeholder = hubState.category.hint,
                                        modifier = Modifier.weight(1f)
                                    )
                                    ModelButton(
                                        text = if (hubState.searching) "搜索中" else "搜索",
                                        onClick = hubViewModel::executeSearch,
                                        enabled = !hubState.searching && hubState.query.isNotBlank()
                                    )
                                }
                                hubState.searchError?.let {
                                    Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
                                }
                                SectionLabel(
                                    if (hubState.showingRecommendations) "推荐起步模型 · 点击查看可下载文件"
                                    else "搜索结果（${hubState.hubSettings.displayName}）"
                                )
                            }
                        }
                        if (hubState.searching) {
                            item(key = "hub-loading") {
                                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = OmniColors.Accent, strokeWidth = 2.dp)
                                }
                            }
                        } else if (hubState.searchResults.isEmpty()) {
                            item(key = "hub-empty") {
                                OmniEmptyState(Icons.Outlined.Search, "没有匹配的模型", "换个关键词，或直接输入完整仓库名 owner/repo")
                            }
                        } else {
                            items(hubState.searchResults, key = { "repo-${it.id}" }) { repo ->
                                HubRepoRow(repo) { hubViewModel.inspectRepo(repo.id) }
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

    hubState.inspectedRepo?.let { repo ->
        HubRepoFilesSheet(
            repo = repo,
            state = hubState,
            onDownload = hubViewModel::downloadOption,
            onDismiss = hubViewModel::closeRepoSheet
        )
    }

    deleteChat?.let { status ->
        ConfirmModelDelete(
            status.spec.name,
            "释放 ${formatBytesCompact(status.spec.totalBytes)} 空间。会话会保留。",
            onConfirm = { llmViewModel.deleteModel(status.spec.id); deleteChat = null },
            onDismiss = { deleteChat = null }
        )
    }
    deleteHubModel?.let { model ->
        ConfirmModelDelete(
            model.name,
            "释放 ${formatBytesCompact(model.totalBytes)} 空间。",
            onConfirm = {
                llmViewModel.unloadIfLoaded(model.id)
                hubViewModel.deleteInstalled(model.id)
                deleteHubModel = null
            },
            onDismiss = { deleteHubModel = null }
        )
    }
    deleteBuiltIn?.let { target ->
        ConfirmModelDelete(
            target.label,
            "将删除手机中已保存的模型文件，之后可重新下载。",
            onConfirm = {
                when (target) {
                    BuiltInDeleteTarget.Decision -> decisionViewModel.deleteModel()
                    BuiltInDeleteTarget.Qwen -> viewModel.deleteQwenModels()
                    BuiltInDeleteTarget.Moss -> viewModel.deleteMossModels()
                    BuiltInDeleteTarget.Yue2 -> viewModel.deleteYue2Models()
                    BuiltInDeleteTarget.Wan -> viewModel.deleteWanModels()
                }
                deleteBuiltIn = null
            },
            onDismiss = { deleteBuiltIn = null }
        )
    }
}

@Composable
private fun DownloadStatusCard(
    active: ActiveDownloadState?,
    queueSize: Int,
    error: String?,
    onCancel: () -> Unit,
    onDismissError: () -> Unit
) {
    ContentCard(
        title = active?.let { "正在下载 · ${it.title}" } ?: "下载提示",
        trailing = {
            if (active != null) {
                TextButton(onClick = onCancel) { Text("取消", color = OmniColors.Danger) }
            } else if (error != null) {
                IconButton(onClick = onDismissError, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, "关闭", tint = OmniColors.Dim, modifier = Modifier.size(18.dp))
                }
            }
        }
    ) {
        if (active != null) {
            if (active.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { active.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = OmniColors.Accent,
                    trackColor = OmniColors.Line,
                    drawStopIndicator = {}
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = OmniColors.Accent,
                    trackColor = OmniColors.Line
                )
            }
            val speed = if (active.bytesPerSecond > 0L) " · ${formatBytesCompact(active.bytesPerSecond)}/s" else ""
            val totalLabel = if (active.totalBytes > 0L) formatBytesCompact(active.totalBytes) else "计算中"
            val queueLabel = if (queueSize > 0) " · 另有 $queueSize 项排队" else ""
            Text(
                "${active.file} · ${formatBytesCompact(active.downloadedBytes)} / $totalLabel$speed$queueLabel",
                color = OmniColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        error?.let {
            Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HubRepoRow(repo: HfRepoSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        repo.name,
                        color = OmniColors.Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (repo.gated) {
                        Icon(Icons.Outlined.Lock, "需授权", tint = OmniColors.Dim, modifier = Modifier.size(14.dp))
                    }
                }
                val meta = buildList {
                    add(repo.author)
                    if (repo.downloads > 0L) add("${formatCount(repo.downloads)} 下载")
                    if (repo.summary.isNotBlank()) add(repo.summary)
                }.joinToString(" · ")
                Text(meta, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.CloudDownload, "查看文件", tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
    }
}

@Composable
private fun HubRepoFilesSheet(
    repo: String,
    state: ModelHubUiState,
    onDownload: (HfRepoOption) -> Unit,
    onDismiss: () -> Unit
) {
    val installedIds = remember(state.installedModels) { state.installedModels.map { it.id }.toSet() }
    OmniSheet(repo, onDismiss) {
        when {
            state.loadingRepo -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = OmniColors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在读取文件列表…", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.repoError != null -> {
                Text(state.repoError, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    state.repoOptions.forEach { option ->
                        val installed = option.id in installedIds
                        val queued = state.downloads.isPendingOrActive("hub:${option.id}")
                        val detail = buildList {
                            option.quantTag?.let(::add)
                            add(formatBytesCompact(option.totalBytes))
                            add("建议 ≥ ${option.recommendedRamGb} GB 内存")
                            if (option.files.size > 1) add("${option.files.size} 个文件")
                        }.joinToString(" · ")
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        option.label,
                                        color = OmniColors.Ink,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(detail, color = OmniColors.Muted, style = MaterialTheme.typography.labelSmall)
                                    option.splitWarning?.let {
                                        Text(it, color = OmniColors.Warning, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                ModelButton(
                                    text = when {
                                        installed -> "已安装"
                                        queued -> "队列中"
                                        else -> "下载"
                                    },
                                    onClick = { onDownload(option) },
                                    enabled = !installed && !queued && option.splitWarning == null
                                )
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MirrorSourceSheet(
    state: ModelHubUiState,
    hubViewModel: ModelHubViewModel,
    onDismiss: () -> Unit
) {
    val settings = state.hubSettings
    var customUrl by remember(settings.customBaseUrl) { mutableStateOf(settings.customBaseUrl) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }
    OmniSheet("模型下载源", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OmniTabs(
                items = HubMirrorMode.entries,
                selected = settings.mode,
                label = {
                    when (it) {
                        HubMirrorMode.Official -> "Hugging Face"
                        HubMirrorMode.HfMirror -> "HF-Mirror"
                        HubMirrorMode.Custom -> "自定义"
                    }
                },
                onSelect = hubViewModel::setMirrorMode
            )
            Text(
                "当前地址：${settings.effectiveBaseUrl}。所有模型搜索与下载均通过此源进行，校验仍以原始 SHA-256 为准。",
                color = OmniColors.Muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (settings.mode == HubMirrorMode.Custom) {
                OmniTextField(
                    value = customUrl,
                    onValueChange = {
                        customUrl = it
                        hubViewModel.setCustomMirrorUrl(it)
                    },
                    label = "自定义镜像根地址",
                    placeholder = "https://hf-mirror.com",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OmniTextField(
                value = token,
                onValueChange = {
                    token = it
                    hubViewModel.setHubToken(it)
                },
                label = "Access Token（可选，用于受限模型）",
                placeholder = "hf_...",
                modifier = Modifier.fillMaxWidth()
            )
            state.settingsError?.let {
                Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModelButton(
                    text = if (state.probing) "测速中…" else "测试连接",
                    onClick = hubViewModel::probeHub,
                    enabled = !state.probing
                )
                state.probeResult?.let { result ->
                    Text(
                        result.message,
                        color = if (result.reachable) OmniColors.Success else OmniColors.Danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    name: String,
    status: String,
    ready: Boolean,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(name, color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                Text(
                    status,
                    color = if (ready) OmniColors.Success else OmniColors.Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing
                )
            }
        }
        below?.invoke()
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
    }
}

@Composable
private fun ModelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, OmniColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) OmniColors.Danger else OmniColors.Accent,
            disabledContentColor = OmniColors.Dim
        ),
        contentPadding = PaddingValues(horizontal = 14.dp)
    ) { Text(text, maxLines = 1) }
}

@Composable
private fun ConfirmModelDelete(name: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除 $name？") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = OmniColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = OmniColors.Raised
    )
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
    else -> value.toString()
}
