package com.tiggy.qwenimage.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import androidx.annotation.StringRes
import com.tiggy.qwenimage.DecisionPhase
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.ModelHubUiState
import com.tiggy.qwenimage.ModelHubViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.ActiveDownloadState
import com.tiggy.qwenimage.data.BenchmarkModality
import com.tiggy.qwenimage.data.DeviceFitLevel
import com.tiggy.qwenimage.data.DeviceHardwareSnapshot
import com.tiggy.qwenimage.data.GenerationBackend
import com.tiggy.qwenimage.data.HfRepoOption
import com.tiggy.qwenimage.data.HfRepoSummary
import com.tiggy.qwenimage.data.HubMirrorMode
import com.tiggy.qwenimage.data.HubSearchCategory
import com.tiggy.qwenimage.data.InstalledHubModel
import com.tiggy.qwenimage.data.LlmModelStatus
import com.tiggy.qwenimage.data.ModelBenchmarkCatalog
import com.tiggy.qwenimage.data.ModelBenchmarkEntry
import com.tiggy.qwenimage.data.formatBytesCompact
import java.util.Locale

private enum class ModelsPageTab(@StringRes val labelRes: Int) {
    Installed(R.string.models_tab_local),
    Discover(R.string.models_tab_discover),
    Reference(R.string.models_tab_benchmarks)
}

private enum class BuiltInDeleteTarget(val label: String) {
    Decision("Elephant-1 9B"),
    Qwen("Qwen-Image"),
    Moss("MOSS-Image"),
    Yue2("YuE2-3B"),
    Wan("Wan2.2")
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
    val context = LocalContext.current
    val localized = LocalLocalizedContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val decisionState by decisionViewModel.state.collectAsStateWithLifecycle()
    val hubState by hubViewModel.state.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    val requestNotificationPermissionIfNeeded = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by rememberSaveable { mutableStateOf(ModelsPageTab.Installed) }
    var benchmarkModality by rememberSaveable { mutableStateOf(BenchmarkModality.Chat) }
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
    val sourceDisplayName = hubState.hubSettings.localizedDisplayName()

    WorkspaceScaffold(
        title = tr(R.string.nav_models),
        subtitle = tr(R.string.models_source_subtitle, sourceDisplayName),
        onMenu = onMenu,
        actions = {
            TextButton(onClick = { showMirrorSheet = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Language, null, tint = OmniColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr(R.string.models_switch_source), color = OmniColors.Accent)
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
                            label = { localized.getString(it.labelRes) },
                            onSelect = { tab = it }
                        )
                        if (hubState.storage.availableBytes > 0L) {
                            Text(
                                tr(
                                    R.string.models_storage_line,
                                    formatBytesCompact(hubState.storage.modelsUsedBytes),
                                    formatBytesCompact(hubState.storage.availableBytes)
                                ),
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
                            item(key = "hub-installed-label") { SectionLabel(tr(R.string.common_community_downloaded), Modifier.padding(top = 12.dp)) }
                            items(hubState.installedModels, key = { "hub-${it.id}" }) { model ->
                                ModelRow(
                                    name = model.name,
                                    status = "${model.type.localizedLabel()} · ${formatBytesCompact(model.totalBytes)} · ${model.repo}",
                                    ready = true,
                                    trailing = {
                                        ModelButton(tr(R.string.common_use), { onUseHubModel(model) })
                                        ModelButton(tr(R.string.common_delete), { deleteHubModel = model }, danger = true)
                                    }
                                )
                            }
                        }

                        item(key = "chat-label") { SectionLabel(tr(R.string.models_sec_chat_builtin), Modifier.padding(top = 16.dp)) }
                        items(llmState.models, key = { "chat-${it.spec.id}" }) { status ->
                            val dlId = "builtin-llm:${status.spec.id}"
                            val queued = downloads.isPendingOrActive(dlId)
                            val complete = status.present == status.spec.artifacts.size
                            ModelRow(
                                name = status.spec.name,
                                status = if (status.ready) tr(R.string.models_verified_size, formatBytesCompact(status.spec.totalBytes))
                                else "${status.message} · ${formatBytesCompact(status.spec.totalBytes)}",
                                ready = status.ready,
                                trailing = {
                                    when {
                                        queued -> ModelButton(tr(R.string.common_cancel), { hubViewModel.cancelQueuedDownload(dlId) })
                                        status.ready -> ModelButton(tr(R.string.common_delete), { deleteChat = status }, enabled = !llmState.busy, danger = true)
                                        complete -> ModelButton(tr(R.string.common_verify), { llmViewModel.verifyModel(status.spec.id) }, enabled = !llmState.busy)
                                        else -> ModelButton(tr(R.string.common_download), {
                                            requestNotificationPermissionIfNeeded()
                                            llmViewModel.downloadModel(status.spec.id)
                                        })
                                    }
                                }
                            )
                        }

                        item(key = "decision-label") { SectionLabel(tr(R.string.models_sec_decision_builtin), Modifier.padding(top = 16.dp)) }
                        item(key = "decision") {
                            val model = decisionState.model
                            val dlId = "builtin-decision:elephant-1"
                            val queued = downloads.isPendingOrActive(dlId)
                            if (model == null) {
                                Text(
                                    tr(R.string.models_reading_status),
                                    color = OmniColors.Muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            } else {
                                val complete = model.present == model.spec.artifacts.size
                                ModelRow(
                                    name = model.spec.name,
                                    status = if (model.ready) tr(R.string.models_verified_size, formatBytesCompact(model.spec.totalBytes))
                                    else "${model.message} · ${formatBytesCompact(model.spec.totalBytes)}",
                                    ready = model.ready,
                                    trailing = {
                                        when {
                                            queued -> ModelButton(tr(R.string.common_cancel), { hubViewModel.cancelQueuedDownload(dlId) })
                                            model.ready -> ModelButton(
                                                tr(R.string.common_delete),
                                                { deleteBuiltIn = BuiltInDeleteTarget.Decision },
                                                enabled = !decisionState.busy,
                                                danger = true
                                            )
                                            complete -> ModelButton(tr(R.string.common_verify), decisionViewModel::verifyModel, enabled = !decisionState.busy)
                                            else -> ModelButton(tr(R.string.common_download), {
                                                requestNotificationPermissionIfNeeded()
                                                decisionViewModel.downloadModel()
                                            })
                                        }
                                    },
                                    below = {
                                        if (decisionState.busy) {
                                            Text(
                                                when (decisionState.phase) {
                                                    DecisionPhase.Verifying -> tr(R.string.decision_verifying)
                                                    DecisionPhase.Loading -> tr(R.string.chat_loading_model)
                                                    DecisionPhase.Computing -> tr(R.string.models_inferring)
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
                                                    if (elephantManualOpen) tr(R.string.models_usb_hide) else tr(R.string.models_usb_import_hint),
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

                        item(key = "image-label") { SectionLabel(tr(R.string.models_sec_image_builtin), Modifier.padding(top = 16.dp)) }
                        item(key = "moss") {
                            val dlId = "builtin-image:moss"
                            val queued = downloads.isPendingOrActive(dlId)
                            ModelRow(
                                name = "MOSS-Image",
                                status = mossStatus?.message ?: tr(R.string.common_not_downloaded),
                                ready = mossStatus?.ready == true,
                                trailing = {
                                    when {
                                        queued -> ModelButton(tr(R.string.common_cancel), { hubViewModel.cancelQueuedDownload(dlId) })
                                        mossStatus?.ready == true -> ModelButton(
                                            tr(R.string.common_delete),
                                            { deleteBuiltIn = BuiltInDeleteTarget.Moss },
                                            enabled = !modelBusy,
                                            danger = true
                                        )
                                        else -> ModelButton(tr(R.string.common_download), {
                                            requestNotificationPermissionIfNeeded()
                                            viewModel.downloadMossModels()
                                        })
                                    }
                                },
                                below = {
                                    if (showModelTask && mossTaskActive) TaskLine(modelTask, modifier = Modifier.padding(bottom = 4.dp))
                                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ModelButton(tr(R.string.models_import_dir), { mossFolderPicker.launch(null) }, enabled = !modelBusy, modifier = Modifier.weight(1f))
                                        ModelButton(
                                            tr(R.string.common_verify),
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
                                name = "Qwen-Image 2.1",
                                status = qwenStatus?.message ?: tr(R.string.common_not_downloaded),
                                ready = qwenStatus?.ready == true,
                                trailing = {
                                    if (qwenStatus?.ready == true) {
                                        ModelButton(
                                            tr(R.string.common_delete),
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
                                            tr(R.string.models_check_engine),
                                            viewModel::checkRuntime,
                                            enabled = !modelBusy
                                        )
                                    }
                                    SettingRow(
                                        title = tr(R.string.models_source_repo),
                                        detail = state.repo.ifBlank { tr(R.string.models_repo_unset) },
                                        onClick = { sourceOpen = !sourceOpen },
                                        trailing = {
                                            Icon(
                                                if (sourceOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                                if (sourceOpen) tr(R.string.models_collapse_source) else tr(R.string.models_expand_source),
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
                                                placeholder = tr(R.string.models_repo_placeholder),
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
                                                    ModelButton(tr(R.string.models_cancel_download), { hubViewModel.cancelQueuedDownload(dlId) }, modifier = Modifier.weight(1f))
                                                } else {
                                                    ModelButton(
                                                        tr(R.string.common_download),
                                                        {
                                                            requestNotificationPermissionIfNeeded()
                                                            viewModel.downloadModels()
                                                        },
                                                        enabled = state.repo.isNotBlank(),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                                ModelButton(
                                                    tr(R.string.models_import_short),
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

                        item(key = "music-label") { SectionLabel(tr(R.string.models_sec_music_builtin), Modifier.padding(top = 16.dp)) }
                        item(key = "yue2") {
                            val dlId = "builtin-music:yue2"
                            val queued = downloads.isPendingOrActive(dlId)
                            val ready = yue2Status?.ready == true
                            ModelRow(
                                name = "YuE2-3B",
                                status = yue2Status?.let { "${it.message} · ${formatBytesCompact(it.totalBytes)}" } ?: "~2.9 GB",
                                ready = ready,
                                trailing = {
                                    when {
                                        queued -> ModelButton(tr(R.string.common_cancel), { hubViewModel.cancelQueuedDownload(dlId) })
                                        ready -> {
                                            if (yue2Status?.verified != true) {
                                                ModelButton(tr(R.string.common_verify), viewModel::verifyYue2Models, enabled = !modelBusy)
                                            }
                                            ModelButton(tr(R.string.common_delete), { deleteBuiltIn = BuiltInDeleteTarget.Yue2 }, enabled = !modelBusy, danger = true)
                                        }
                                        else -> ModelButton(tr(R.string.common_download), {
                                            requestNotificationPermissionIfNeeded()
                                            viewModel.downloadYue2Models()
                                        })
                                    }
                                },
                                below = {
                                    Text(
                                        tr(R.string.models_cc_by_nc_note),
                                        color = OmniColors.Dim,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            )
                        }

                        item(key = "video-label") { SectionLabel(tr(R.string.models_sec_video_builtin), Modifier.padding(top = 16.dp)) }
                        item(key = "wan22") {
                            val dlId = "builtin-video:wan22"
                            val queued = downloads.isPendingOrActive(dlId)
                            val ready = wanStatus?.ready == true
                            ModelRow(
                                name = "Wan2.2 Turbo",
                                status = wanStatus?.let { "${it.message} · ${formatBytesCompact(it.totalBytes)}" } ?: "~5.9 GB",
                                ready = ready,
                                trailing = {
                                    when {
                                        queued -> ModelButton(tr(R.string.common_cancel), { hubViewModel.cancelQueuedDownload(dlId) })
                                        ready -> {
                                            if (wanStatus?.verified != true) {
                                                ModelButton(tr(R.string.common_verify), viewModel::verifyWanModels, enabled = !modelBusy)
                                            }
                                            ModelButton(tr(R.string.common_delete), { deleteBuiltIn = BuiltInDeleteTarget.Wan }, enabled = !modelBusy, danger = true)
                                        }
                                        else -> ModelButton(tr(R.string.common_download), {
                                            requestNotificationPermissionIfNeeded()
                                            viewModel.downloadWanModels()
                                        })
                                    }
                                },
                                below = {
                                    Text(
                                        tr(R.string.models_wan_note),
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
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(HubSearchCategory.entries) { cat ->
                                        ComposerChip(
                                            label = cat.localizedLabel(),
                                            onClick = { hubViewModel.selectCategory(cat) },
                                            selected = hubState.category == cat
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val submitSearch = {
                                        if (!hubState.searching && hubState.query.isNotBlank()) {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            hubViewModel.executeSearch()
                                        }
                                    }
                                    OmniTextField(
                                        value = hubState.query,
                                        onValueChange = hubViewModel::updateQuery,
                                        label = tr(R.string.models_search_placeholder),
                                        placeholder = hubState.category.hint,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                                        trailingIcon = if (hubState.query.isNotEmpty()) ({
                                            IconButton(onClick = { hubViewModel.updateQuery("") }) {
                                                Icon(Icons.Outlined.Close, tr(R.string.common_clear), tint = OmniColors.Dim, modifier = Modifier.size(18.dp))
                                            }
                                        }) else null,
                                        modifier = Modifier.weight(1f)
                                    )
                                    ModelButton(
                                        text = if (hubState.searching) tr(R.string.models_searching) else tr(R.string.common_search),
                                        onClick = submitSearch,
                                        enabled = !hubState.searching && hubState.query.isNotBlank()
                                    )
                                }
                                hubState.searchError?.let {
                                    Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
                                }
                                SectionLabel(
                                    if (hubState.showingRecommendations) tr(R.string.models_starter_header)
                                    else tr(R.string.models_search_results, sourceDisplayName)
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
                                OmniEmptyState(Icons.Outlined.Search, tr(R.string.models_empty_search), tr(R.string.models_empty_search_hint))
                            }
                        } else {
                            items(hubState.searchResults, key = { "repo-${it.id}" }) { repo ->
                                HubRepoRow(repo) { hubViewModel.inspectRepo(repo.id) }
                            }
                        }
                    }

                    ModelsPageTab.Reference -> {
                        item(key = "ref-header") {
                            Column(
                                Modifier.padding(top = 12.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(BenchmarkModality.entries) { modality ->
                                        ComposerChip(
                                            label = modality.localizedLabel(),
                                            onClick = { benchmarkModality = modality },
                                            selected = benchmarkModality == modality
                                        )
                                    }
                                }
                                ContentCard(title = tr(R.string.bench_guide_title)) {
                                    val hwNote = hubState.deviceHardware?.localizedSummary()?.let {
                                        tr(R.string.bench_device_prefix, it)
                                    }.orEmpty()
                                    Text(
                                        tr(R.string.bench_guide_body, hwNote),
                                        color = OmniColors.Muted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        val entries = ModelBenchmarkCatalog.byModality(benchmarkModality)
                        itemsIndexed(entries, key = { _, entry -> "bench-${entry.id}" }) { index, entry ->
                            BenchmarkReferenceRow(
                                rank = index + 1,
                                entry = entry,
                                device = hubState.deviceHardware,
                                onInspect = if (entry.recommendedRepo != null && entry.searchCategory != null) {
                                    { hubViewModel.openRepoInCategory(entry.recommendedRepo, entry.searchCategory) }
                                } else null
                            )
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
            onDownload = { option ->
                requestNotificationPermissionIfNeeded()
                hubViewModel.downloadOption(option)
            },
            onDismiss = hubViewModel::closeRepoSheet
        )
    }

    deleteChat?.let { status ->
        ConfirmModelDelete(
            status.spec.name,
            tr(R.string.models_free_space_keep_chats, formatBytesCompact(status.spec.totalBytes)),
            onConfirm = { llmViewModel.deleteModel(status.spec.id); deleteChat = null },
            onDismiss = { deleteChat = null }
        )
    }
    deleteHubModel?.let { model ->
        ConfirmModelDelete(
            model.name,
            tr(R.string.models_free_space, formatBytesCompact(model.totalBytes)),
            onConfirm = {
                hubViewModel.deleteInstalled(model.id) {
                    llmViewModel.unloadIfLoadedSync(model.id)
                }
                deleteHubModel = null
            },
            onDismiss = { deleteHubModel = null }
        )
    }
    deleteBuiltIn?.let { target ->
        ConfirmModelDelete(
            target.label,
            tr(R.string.models_delete_generic_body),
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
        title = active?.let { tr(R.string.models_downloading_title, it.title) } ?: tr(R.string.models_download_notice),
        trailing = {
            if (active != null) {
                TextButton(onClick = onCancel) { Text(tr(R.string.common_cancel), color = OmniColors.Danger) }
            } else if (error != null) {
                IconButton(onClick = onDismissError, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, tr(R.string.common_close), tint = OmniColors.Dim, modifier = Modifier.size(18.dp))
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
            val totalLabel = if (active.totalBytes > 0L) formatBytesCompact(active.totalBytes) else tr(R.string.models_calculating)
            val queueLabel = if (queueSize > 0) tr(R.string.models_queue_more, queueSize) else ""
            if (active.file.isNotBlank() && active.file != active.title) {
                Text(
                    active.file,
                    color = OmniColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${formatBytesCompact(active.downloadedBytes)} / $totalLabel$speed$queueLabel",
                color = OmniColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
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
    val benchmark = remember(repo.id) { ModelBenchmarkCatalog.matchRepoOrName(repo.id) }
    val trusted = remember(repo.author) { ModelBenchmarkCatalog.isTrustedPackager(repo.author) }
    val authorText = if (trusted) tr(R.string.models_trusted_packager, repo.author) else repo.author
    val downloadsText = if (repo.downloads > 0L) tr(R.string.models_downloads_count, formatCount(repo.downloads)) else null
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
                        Icon(Icons.Outlined.Lock, tr(R.string.models_gated), tint = OmniColors.Dim, modifier = Modifier.size(14.dp))
                    }
                }
                val meta = buildList {
                    add(authorText)
                    downloadsText?.let(::add)
                    if (repo.summary.isNotBlank()) add(repo.summary)
                }.joinToString(" · ")
                Text(meta, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (benchmark != null) {
                    Text(
                        tr(R.string.bench_ref_line, benchmark.familyName, benchmark.primaryScoreLabel, benchmark.evidenceTier.localizedLabel()),
                        color = OmniColors.Dim,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(Icons.Outlined.CloudDownload, tr(R.string.models_view_files), tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
        }
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
    }
}

@Composable
private fun BenchmarkReferenceRow(
    rank: Int,
    entry: ModelBenchmarkEntry,
    device: DeviceHardwareSnapshot?,
    onInspect: (() -> Unit)?
) {
    val ramFit = remember(entry.minRamGb, device) {
        if (device == null || device.totalRamGb <= 0.0) null
        else when {
            device.totalRamGb >= entry.minRamGb * 1.25 -> DeviceFitLevel.Smooth
            device.totalRamGb >= entry.minRamGb * 0.9 -> DeviceFitLevel.Usable
            device.totalRamGb >= entry.minRamGb * 0.72 -> DeviceFitLevel.Tight
            else -> DeviceFitLevel.LikelyOom
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                "$rank",
                color = OmniColors.Dim,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(24.dp).padding(top = 2.dp)
            )
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${entry.familyName} · ${entry.paramScale}",
                    color = OmniColors.Ink,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                val scores = listOf(entry.primaryScoreLabel, entry.secondaryScoreLabel)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                Text(scores, color = OmniColors.Accent, style = MaterialTheme.typography.labelMedium)
                Text(entry.strengths, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
                val fitText = ramFit?.let { " · ${it.localizedLabel()}" }.orEmpty()
                Text(
                    tr(R.string.bench_rec_line, entry.recommendedQuant, entry.minRamGb, fitText),
                    color = deviceFitColor(ramFit),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    tr(R.string.bench_source_line, entry.source, entry.evidenceTier.localizedLabel()),
                    color = OmniColors.Dim,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (onInspect != null) {
                ModelButton(tr(R.string.models_view_downloads), onInspect)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
    }
}

@Composable
private fun deviceFitColor(level: DeviceFitLevel?) = when (level) {
    DeviceFitLevel.Smooth -> OmniColors.Success
    DeviceFitLevel.Usable, null -> OmniColors.Muted
    DeviceFitLevel.Tight -> OmniColors.Warning
    DeviceFitLevel.LikelyOom -> OmniColors.Danger
}

@Composable
private fun HubRepoFilesSheet(
    repo: String,
    state: ModelHubUiState,
    onDownload: (HfRepoOption) -> Unit,
    onDismiss: () -> Unit
) {
    val installedIds = remember(state.installedModels) { state.installedModels.map { it.id }.toSet() }
    val benchmark = remember(repo) { ModelBenchmarkCatalog.matchRepoOrName(repo) }
    OmniSheet(repo, onDismiss) {
        if (benchmark != null) {
            Text(
                tr(R.string.bench_ref_line, benchmark.familyName, benchmark.primaryScoreLabel, benchmark.source),
                color = OmniColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        when {
            state.loadingRepo -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = OmniColors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(tr(R.string.models_loading_files), color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
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
                        val quantHint = remember(option.quantTag) { ModelBenchmarkCatalog.quantQualityHint(option.quantTag) }
                        val fit = remember(option.totalBytes, state.deviceHardware) {
                            ModelBenchmarkCatalog.evaluateDeviceFit(option.totalBytes, state.deviceHardware)
                        }
                        val recRamText = tr(R.string.models_rec_ram, option.recommendedRamGb)
                        val filesCountText = if (option.files.size > 1) tr(R.string.models_files_count, option.files.size) else null
                        val detail = buildList {
                            option.quantTag?.let(::add)
                            add(formatBytesCompact(option.totalBytes))
                            add(recRamText)
                            filesCountText?.let(::add)
                        }.joinToString(" · ")
                        val fitLabel = fit.localizedLabel()
                        val quantText = quantHint?.let { tr(R.string.models_quant_retention, it.retentionPercent, it.note) }
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
                                    val fitLine = listOfNotNull(fitLabel, quantText).joinToString(" · ")
                                    Text(fitLine, color = deviceFitColor(fit), style = MaterialTheme.typography.labelSmall)
                                    option.companionSummary?.let {
                                        Text(it, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
                                    }
                                    option.splitWarning?.let {
                                        Text(it, color = OmniColors.Warning, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                ModelButton(
                                    text = when {
                                        installed -> tr(R.string.models_installed_badge)
                                        queued -> tr(R.string.image_status_queued)
                                        else -> tr(R.string.common_download)
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
    val customLabel = tr(R.string.common_custom)
    var customUrl by remember(settings.customBaseUrl) { mutableStateOf(settings.customBaseUrl) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }
    OmniSheet(tr(R.string.models_mirror_title), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OmniTabs(
                items = HubMirrorMode.entries,
                selected = settings.mode,
                label = {
                    when (it) {
                        HubMirrorMode.Official -> "Hugging Face"
                        HubMirrorMode.HfMirror -> "HF-Mirror"
                        HubMirrorMode.Custom -> customLabel
                    }
                },
                onSelect = hubViewModel::setMirrorMode
            )
            Text(
                tr(R.string.models_mirror_desc, settings.effectiveBaseUrl),
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
                    label = tr(R.string.models_mirror_custom_url),
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
                label = tr(R.string.models_mirror_token),
                placeholder = "hf_...",
                modifier = Modifier.fillMaxWidth()
            )
            state.settingsError?.let {
                Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModelButton(
                    text = if (state.probing) tr(R.string.models_testing) else tr(R.string.models_test_conn),
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
        title = { Text(tr(R.string.models_delete_confirm, name)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr(R.string.common_delete), color = OmniColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.common_cancel)) } },
        containerColor = OmniColors.Raised
    )
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
    else -> value.toString()
}
