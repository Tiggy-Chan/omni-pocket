package com.tiggy.qwenimage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.KnowledgeBaseViewModel
import com.tiggy.qwenimage.LlmPhase
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.ModelHubViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.AppUpdateManager
import com.tiggy.qwenimage.data.AppUpdateUiState
import com.tiggy.qwenimage.data.HubModelType
import com.tiggy.qwenimage.data.TaskDomain
import com.tiggy.qwenimage.data.formatBytesCompact
import kotlinx.coroutines.launch
import java.io.File

/**
 * App root. One sidebar (drawer on phones, permanent pane at 840dp and wider) switches between the
 * five workspaces and the work directory, models and settings pages. Each destination draws its
 * own [WorkspaceScaffold]; [onMenu] is null when the sidebar is permanently visible.
 */
@Composable
fun QwenApp(
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    decisionViewModel: DecisionViewModel,
    hubViewModel: ModelHubViewModel,
    kbViewModel: KnowledgeBaseViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val decisionState by decisionViewModel.state.collectAsStateWithLifecycle()
    val updateState by AppUpdateManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersion = remember(context) { AppUpdateManager.currentAppVersion(context) }
    var page by rememberSaveable { mutableStateOf(AppPage.Workspace) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        AppUpdateManager.autoCheckOnce(context)
    }

    val activity = WorkspaceActivity(buildSet {
        if (llmState.phase == LlmPhase.Generating || llmState.phase == LlmPhase.Loading) add(AppDomain.Conversation)
        if (decisionState.busy) add(AppDomain.Decision)
        when (state.activeTask?.domain) {
            TaskDomain.Image -> add(AppDomain.ImageStudio)
            TaskDomain.Music -> add(AppDomain.MusicStudio)
            TaskDomain.Video -> add(AppDomain.VideoStudio)
            else -> Unit
        }
    })

    BoxWithConstraints(Modifier.fillMaxSize().background(OmniColors.Background)) {
        val wide = maxWidth >= 840.dp
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val openMenu: (() -> Unit)? = if (wide) null else ({ scope.launch { drawer.open() } })
        val sidebar = @Composable {
            OmniSidebar(
                domain = state.domain,
                page = page,
                activity = activity,
                llmState = llmState,
                llmViewModel = llmViewModel,
                onDomain = viewModel::setDomain,
                onPage = { page = it },
                onNavigated = { if (!wide) scope.launch { drawer.close() } },
                appVersion = appVersion,
                hasAppUpdate = updateState.hasUpdate,
                onVersionClick = {
                    if (updateState.hasUpdate) AppUpdateManager.openUpdateDialog()
                    else page = AppPage.About
                }
            )
        }
        val destination = @Composable {
            Destination(page, state, viewModel, llmViewModel, decisionViewModel, hubViewModel, kbViewModel, openMenu,
                onPreview = { previewImagePath = it }, onPage = { page = it })
        }
        BackHandler(enabled = !wide && drawer.isOpen) { scope.launch { drawer.close() } }
        BackHandler(enabled = page != AppPage.Workspace && !drawer.isOpen) { page = AppPage.Workspace }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                sidebar()
                VerticalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                Box(Modifier.weight(1f)) { destination() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                scrimColor = LocalOmniPalette.current.scrim,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(296.dp),
                        drawerContainerColor = OmniColors.Sidebar,
                        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
                    ) { sidebar() }
                }
            ) { destination() }
        }
    }

    state.alert?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(tr(R.string.common_action_needed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(tr(R.string.common_got_it)) } },
            containerColor = OmniColors.Raised
        )
    }

    previewImagePath?.let { path ->
        ImagePreviewDialog(path, viewModel, onDismiss = { previewImagePath = null })
    }

    if (updateState.showDialog && updateState.latest != null) {
        AppUpdateDialog(
            state = updateState,
            onDownloadOrInstall = {
                val ready = updateState.readyApkPath?.let(::File)?.takeIf { it.isFile }
                if (ready != null) {
                    AppUpdateManager.launchApkInstaller(context, ready)
                } else {
                    AppUpdateManager.startUpdateDownload(context)
                }
            },
            onCancelDownload = AppUpdateManager::cancelDownload,
            onIgnore = { AppUpdateManager.ignoreCurrentUpdate(context) },
            onDismiss = AppUpdateManager::dismissDialog
        )
    }
}

@Composable
private fun AppUpdateDialog(
    state: AppUpdateUiState,
    onDownloadOrInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit
) {
    val latest = state.latest ?: return
    val localizedCtx = LocalLocalizedContext.current
    val preferZh = remember(localizedCtx) {
        localizedCtx.resources.configuration.locales[0]?.language?.startsWith("zh") == true
    }
    val notes = remember(latest, preferZh) { latest.localizedNotes(preferZh) }
    val readyFile = state.readyApkPath?.let(::File)?.takeIf { it.isFile }

    AlertDialog(
        onDismissRequest = { if (!state.downloading) onDismiss() },
        containerColor = OmniColors.Raised,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.update_dialog_title, latest.versionName), color = OmniColors.Ink)
                UpdateNewBadge()
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val meta = buildList {
                    if (state.currentVersion.isNotBlank()) add("v${state.currentVersion} → v${latest.versionName}")
                    if (latest.apkSizeBytes > 0L) add(formatBytesCompact(latest.apkSizeBytes))
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, color = OmniColors.Muted, style = MaterialTheme.typography.labelMedium)
                }
                if (notes.isNotBlank()) {
                    Text(notes, color = OmniColors.Ink, style = MaterialTheme.typography.bodySmall)
                }
                if (state.downloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val pct = (state.progress * 100f).toInt().coerceIn(0, 100)
                        val progressText = if (state.totalBytes > 0L) {
                            tr(
                                R.string.update_downloading_fmt,
                                pct,
                                formatBytesCompact(state.downloadedBytes),
                                formatBytesCompact(state.totalBytes)
                            )
                        } else {
                            tr(R.string.update_downloading_preparing)
                        }
                        Text(progressText, color = OmniColors.Accent, style = MaterialTheme.typography.labelSmall)
                        if (state.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = OmniColors.Accent,
                                trackColor = OmniColors.Field
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = OmniColors.Accent,
                                trackColor = OmniColors.Field
                            )
                        }
                    }
                }
                state.error?.let { err ->
                    Text(err, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (state.downloading) {
                TextButton(onClick = onCancelDownload) {
                    Text(tr(R.string.common_cancel), color = OmniColors.Danger)
                }
            } else {
                TextButton(onClick = onDownloadOrInstall) {
                    Text(
                        if (readyFile != null) tr(R.string.update_install_now) else tr(R.string.update_download_now),
                        color = OmniColors.Accent
                    )
                }
            }
        },
        dismissButton = {
            if (!state.downloading) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onIgnore) {
                        Text(tr(R.string.update_ignore_version), color = OmniColors.Dim)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(tr(R.string.update_later), color = OmniColors.Muted)
                    }
                }
            }
        }
    )
}

@Composable
private fun Destination(
    page: AppPage,
    state: QwenUiState,
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    decisionViewModel: DecisionViewModel,
    hubViewModel: ModelHubViewModel,
    kbViewModel: KnowledgeBaseViewModel,
    onMenu: (() -> Unit)?,
    onPreview: (String) -> Unit,
    onPage: (AppPage) -> Unit
) {
    when (page) {
        AppPage.Workspace -> when (state.domain) {
            AppDomain.Conversation -> LlmChatScreen(
                viewModel = llmViewModel,
                kbViewModel = kbViewModel,
                onMenu = onMenu,
                onModels = { onPage(AppPage.Models) },
                onManageKnowledgeBases = { onPage(AppPage.KnowledgeBase) }
            )
            AppDomain.Decision -> DecisionScreen(decisionViewModel, onMenu, onModels = { onPage(AppPage.Models) })
            AppDomain.ImageStudio -> ImageWorkspace(state, viewModel, onMenu, onPreview, onModels = { onPage(AppPage.Models) })
            AppDomain.MusicStudio -> MusicWorkspace(state, viewModel, onMenu, onModels = { onPage(AppPage.Models) })
            AppDomain.VideoStudio -> VideoWorkspace(state, viewModel, onMenu, onModels = { onPage(AppPage.Models) })
        }
        AppPage.KnowledgeBase -> KnowledgeBasePage(
            kbViewModel = kbViewModel,
            onMenu = onMenu,
            onUseInChat = { base ->
                llmViewModel.selectKnowledgeBase(base.id, base.name)
                viewModel.setDomain(AppDomain.Conversation)
                onPage(AppPage.Workspace)
            },
            onOpenModels = { onPage(AppPage.Models) }
        )
        AppPage.Library -> LibraryScreen(state, viewModel, onMenu, onPreview,
            onOpenWorkspace = { domain -> viewModel.setDomain(domain); onPage(AppPage.Workspace) })
        AppPage.Models -> ModelsPage(
            state = state,
            viewModel = viewModel,
            llmViewModel = llmViewModel,
            decisionViewModel = decisionViewModel,
            hubViewModel = hubViewModel,
            onMenu = onMenu,
            onUseHubModel = { model ->
                when (model.type) {
                    HubModelType.GgufChat, HubModelType.MnnChat -> {
                        llmViewModel.selectModel(model.id)
                        viewModel.setDomain(AppDomain.Conversation)
                        onPage(AppPage.Workspace)
                    }
                    HubModelType.DecisionGguf -> {
                        decisionViewModel.selectModel(model.id)
                        viewModel.setDomain(AppDomain.Decision)
                        onPage(AppPage.Workspace)
                    }
                    HubModelType.SdImage -> {
                        viewModel.selectCustomImageModel(model.id)
                        viewModel.setDomain(AppDomain.ImageStudio)
                        onPage(AppPage.Workspace)
                    }
                    HubModelType.VideoWan -> {
                        viewModel.selectVideoModel(model.id)
                        viewModel.setDomain(AppDomain.VideoStudio)
                        onPage(AppPage.Workspace)
                    }
                    HubModelType.MusicAudio -> {
                        viewModel.selectMusicModel(model.id)
                        viewModel.setDomain(AppDomain.MusicStudio)
                        onPage(AppPage.Workspace)
                    }
                    HubModelType.Embedding -> {
                        kbViewModel.selectEmbeddingModel(model.id)
                        onPage(AppPage.KnowledgeBase)
                    }
                }
            }
        )
        AppPage.Settings -> SettingsPage(state, viewModel, llmViewModel, decisionViewModel, hubViewModel, onMenu)
        AppPage.About -> AboutPage(onMenu)
    }
}

/** Full-screen image viewer with the follow-up actions a finished image supports. */
@Composable
private fun ImagePreviewDialog(path: String, viewModel: QwenViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shareTitle = tr(R.string.image_share_title)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, tr(R.string.common_close_preview), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                OmniAsyncImage(path, null, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), maxDimension = 2048)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDismiss(); viewModel.editImage(path) }) { Text(tr(R.string.common_continue_edit), color = androidx.compose.ui.graphics.Color.White) }
                TextButton(onClick = { onDismiss(); viewModel.useImageAsVideoFirstFrame(path) }) { Text(tr(R.string.common_make_video), color = androidx.compose.ui.graphics.Color.White) }
                TextButton(onClick = {
                    runCatching { shareMediaFile(context, java.io.File(path), "image/png", shareTitle) }
                }) { Text(tr(R.string.common_share), color = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

@Composable
internal fun PageTitle(text: String) {
    Text(text, color = OmniColors.Ink, style = MaterialTheme.typography.titleLarge)
}
