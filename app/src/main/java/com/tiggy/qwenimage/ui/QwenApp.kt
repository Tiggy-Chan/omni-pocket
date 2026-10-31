package com.tiggy.qwenimage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.LlmPhase
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.TaskDomain
import kotlinx.coroutines.launch

/**
 * App root. One sidebar (drawer on phones, permanent pane at 840dp and wider) switches between the
 * five workspaces and the work directory, models and settings pages. Each destination draws its
 * own [WorkspaceScaffold]; [onMenu] is null when the sidebar is permanently visible.
 */
@Composable
fun QwenApp(
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    decisionViewModel: DecisionViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val decisionState by decisionViewModel.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(AppPage.Workspace) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
                onNavigated = { if (!wide) scope.launch { drawer.close() } }
            )
        }
        val destination = @Composable {
            Destination(page, state, viewModel, llmViewModel, decisionViewModel, openMenu,
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
            title = { Text("需要处理") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("知道了") } },
            containerColor = OmniColors.Raised
        )
    }

    previewImagePath?.let { path ->
        ImagePreviewDialog(path, viewModel, onDismiss = { previewImagePath = null })
    }
}

@Composable
private fun Destination(
    page: AppPage,
    state: QwenUiState,
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    decisionViewModel: DecisionViewModel,
    onMenu: (() -> Unit)?,
    onPreview: (String) -> Unit,
    onPage: (AppPage) -> Unit
) {
    when (page) {
        AppPage.Workspace -> when (state.domain) {
            AppDomain.Conversation -> LlmChatScreen(llmViewModel, onMenu, onModels = { onPage(AppPage.Models) })
            AppDomain.Decision -> DecisionScreen(decisionViewModel, onMenu, onModels = { onPage(AppPage.Models) })
            AppDomain.ImageStudio -> ImageWorkspace(state, viewModel, onMenu, onPreview, onModels = { onPage(AppPage.Models) })
            AppDomain.MusicStudio -> MusicWorkspace(state, viewModel, onMenu, onSettings = { onPage(AppPage.Settings) })
            AppDomain.VideoStudio -> VideoWorkspace(state, viewModel, onMenu, onModels = { onPage(AppPage.Models) })
        }
        AppPage.Library -> LibraryScreen(state, viewModel, onMenu, onPreview,
            onOpenWorkspace = { domain -> viewModel.setDomain(domain); onPage(AppPage.Workspace) })
        AppPage.Models -> ModelsPage(state, viewModel, llmViewModel, decisionViewModel, onMenu)
        AppPage.Settings -> SettingsPage(state, viewModel, onMenu)
    }
}

/** Full-screen image viewer with the follow-up actions a finished image supports. */
@Composable
private fun ImagePreviewDialog(path: String, viewModel: QwenViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, "关闭预览", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                OmniAsyncImage(path, "作品大图", Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), maxDimension = 2048)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDismiss(); viewModel.editImage(path) }) { Text("继续编辑", color = androidx.compose.ui.graphics.Color.White) }
                TextButton(onClick = { onDismiss(); viewModel.useImageAsVideoFirstFrame(path) }) { Text("生成视频", color = androidx.compose.ui.graphics.Color.White) }
                TextButton(onClick = {
                    runCatching { shareMediaFile(context, java.io.File(path), "image/png", "分享图像") }
                }) { Text("分享", color = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

@Composable
internal fun PageTitle(text: String) {
    Text(text, color = OmniColors.Ink, style = MaterialTheme.typography.titleLarge)
}
