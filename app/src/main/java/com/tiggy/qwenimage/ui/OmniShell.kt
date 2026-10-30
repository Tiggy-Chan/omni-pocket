package com.tiggy.qwenimage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.LlmPhase
import com.tiggy.qwenimage.LlmUiState
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.LlmConversation

/** Top-level destinations outside the five workspaces. */
enum class AppPage { Workspace, Library, Models, Settings }

/** What is running in each workspace, shown as a dot in the sidebar. */
data class WorkspaceActivity(val running: Set<AppDomain> = emptySet())

fun AppDomain.icon(): ImageVector = when (this) {
    AppDomain.Conversation -> Icons.AutoMirrored.Outlined.Chat
    AppDomain.Decision -> Icons.AutoMirrored.Outlined.FactCheck
    AppDomain.ImageStudio -> Icons.Outlined.Image
    AppDomain.MusicStudio -> Icons.Outlined.MusicNote
    AppDomain.VideoStudio -> Icons.Outlined.Videocam
}

/**
 * The one navigation surface of the app: workspaces, the conversation history of the chat
 * workspace, and the work directory, models and settings pages. Used as a modal drawer on phones
 * and as a permanent pane on wide screens.
 */
@Composable
fun OmniSidebar(
    domain: AppDomain,
    page: AppPage,
    activity: WorkspaceActivity,
    llmState: LlmUiState,
    llmViewModel: LlmViewModel,
    onDomain: (AppDomain) -> Unit,
    onPage: (AppPage) -> Unit,
    onNavigated: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<LlmConversation?>(null) }
    var deleteFor by remember { mutableStateOf<LlmConversation?>(null) }
    val inChat = page == AppPage.Workspace && domain == AppDomain.Conversation
    Column(
        modifier.fillMaxHeight().width(296.dp).background(OmniColors.Sidebar).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Omni Pocket", color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Surface(
            onClick = {
                llmViewModel.createConversation()
                onPage(AppPage.Workspace)
                onDomain(AppDomain.Conversation)
                onNavigated()
            },
            enabled = llmState.ready,
            shape = RoundedCornerShape(12.dp),
            color = OmniColors.Raised,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, OmniColors.Line),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        ) {
            Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, null, tint = OmniColors.Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("新对话", color = OmniColors.Ink, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("工作区", Modifier.padding(start = 10.dp))
        AppDomain.entries.forEach { item ->
            SidebarRow(
                label = item.title,
                icon = item.icon(),
                selected = page == AppPage.Workspace && domain == item,
                running = item in activity.running,
                onClick = {
                    onPage(AppPage.Workspace)
                    onDomain(item)
                    onNavigated()
                }
            )
        }
        if (inChat) {
            SectionLabel("最近对话", Modifier.padding(start = 10.dp, top = 12.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (inChat) {
                items(llmState.conversations.sortedByDescending { it.updatedAt }, key = { it.id }) { conversation ->
                    val selected = conversation.id == llmState.selectedId
                    val running = llmState.phase == LlmPhase.Generating && llmState.workingModelId != null &&
                        conversation.messages.lastOrNull()?.status == com.tiggy.qwenimage.data.LlmMessageStatus.Streaming
                    Surface(
                        onClick = { llmViewModel.selectConversation(conversation.id); onNavigated() },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) OmniColors.Selected else androidx.compose.ui.graphics.Color.Transparent
                    ) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(conversation.title, color = if (selected) OmniColors.Ink else OmniColors.Muted,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (running) RunningDot()
                            Box {
                                IconButton(onClick = { menuFor = conversation.id }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Outlined.MoreHoriz, "对话操作", tint = OmniColors.Dim, modifier = Modifier.size(18.dp))
                                }
                                DropdownMenu(expanded = menuFor == conversation.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = { renameFor = conversation; menuFor = null })
                                    DropdownMenuItem(text = { Text("删除", color = OmniColors.Danger) },
                                        onClick = { deleteFor = conversation; menuFor = null })
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
        Spacer(Modifier.height(4.dp))
        SidebarRow("工作目录", Icons.Outlined.FolderOpen, page == AppPage.Library) { onPage(AppPage.Library); onNavigated() }
        SidebarRow("模型", Icons.Outlined.Memory, page == AppPage.Models) { onPage(AppPage.Models); onNavigated() }
        SidebarRow("设置", Icons.Outlined.Settings, page == AppPage.Settings) { onPage(AppPage.Settings); onNavigated() }
        Spacer(Modifier.height(8.dp))
    }
    renameFor?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(onDismissRequest = { renameFor = null }, title = { Text("重命名") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { llmViewModel.renameConversation(conversation.id, title); renameFor = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } })
    }
    deleteFor?.let { conversation ->
        AlertDialog(onDismissRequest = { deleteFor = null }, title = { Text("删除这个对话？") },
            text = { Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { llmViewModel.deleteConversation(conversation.id); deleteFor = null }) {
                Text("删除", color = OmniColors.Danger)
            } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } })
    }
}

@Composable
private fun SidebarRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    running: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) OmniColors.Selected else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) OmniColors.Ink else OmniColors.Muted, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (selected) OmniColors.Ink else OmniColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
            if (running) RunningDot()
        }
    }
}

@Composable
private fun RunningDot() {
    Box(Modifier.padding(horizontal = 8.dp).size(6.dp).clip(CircleShape).background(OmniColors.Accent))
}
