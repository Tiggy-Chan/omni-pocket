package com.tiggy.qwenimage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.LlmPhase
import com.tiggy.qwenimage.LlmUiState
import com.tiggy.qwenimage.LlmUsage
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.LlmMessage
import com.tiggy.qwenimage.data.LlmMessageStatus
import java.util.Locale

@Composable
fun LlmChatScreen(viewModel: LlmViewModel, onMenu: (() -> Unit)?, onModels: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val conversation = state.conversation
    val selectedModel = state.selectedModelChoice
    val modelName = selectedModel?.name ?: if (conversation?.modelId.isNullOrBlank()) tr(R.string.chat_models_sheet_title) else tr(R.string.chat_no_model)
    val thinking = conversation?.thinking == true
    val loading = state.phase == LlmPhase.Loading
    val running = loading || state.phase == LlmPhase.Generating

    state.alert?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearAlert,
            title = { Text(tr(R.string.common_action_needed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearAlert) { Text(tr(R.string.common_got_it)) } },
            containerColor = OmniColors.Raised
        )
    }
    if (picking) {
        ModelPicker(
            state = state,
            selectedId = conversation?.modelId,
            onSelect = { id -> viewModel.selectModel(id); picking = false },
            onManage = { picking = false; onModels() },
            onDismiss = { picking = false }
        )
    }

    WorkspaceScaffold(
        title = conversation?.title ?: tr(R.string.nav_chat),
        subtitle = modelName,
        onMenu = onMenu,
        actions = {
            IconButton(onClick = { viewModel.setThinking(!thinking) }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (thinking) OmniColors.AccentSoft else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Psychology, tr(R.string.chat_deep_thinking),
                        tint = (if (thinking) OmniColors.Accent else OmniColors.Muted).copy(alpha = if (state.busy) 0.5f else 1f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        composer = { haze ->
            val status: (@Composable () -> Unit)? = if (loading || state.error != null) ({
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (loading) Text(tr(R.string.chat_loading_model), color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall) }
                }
            }) else null
            OmniComposer(
                hazeState = haze,
                value = state.draft,
                onValueChange = viewModel::updateDraft,
                placeholder = tr(R.string.chat_input_placeholder),
                onSend = viewModel::send,
                sendEnabled = !state.busy && state.draft.isNotBlank(),
                running = running,
                onStop = viewModel::cancel,
                enabled = state.ready,
                status = status,
                tools = { ModelChip(modelName, enabled = !state.busy) { picking = true } }
            )
        }
    ) { padding ->
        Transcript(state, padding, onModels, onRegenerate = viewModel::regenerateLast)
    }
}

@Composable
private fun Transcript(state: LlmUiState, padding: PaddingValues, onModels: () -> Unit, onRegenerate: () -> Unit) {
    val listState = rememberLazyListState()
    val conversation = state.conversation
    val messages = conversation?.messages.orEmpty()
    val atBottom by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == listState.layoutInfo.totalItemsCount - 1 }
    }
    LaunchedEffect(conversation?.id, messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex, 100_000)
    }
    val tail = messages.lastOrNull()?.let { it.content.length + it.reasoning.length }
    LaunchedEffect(tail) {
        if (atBottom && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex, 100_000)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (messages.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr(R.string.chat_start_title), color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium)
                        if (state.ready && !state.modelReady) {
                            Text(tr(R.string.chat_start_subtitle), color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onModels) { Text(tr(R.string.common_open_models)) }
                        }
                    }
                }
            }
        } else {
            val lastId = messages.last().id
            items(messages, key = { it.id }) { message ->
                ReadingColumn {
                    val isLast = message.id == lastId
                    MessageItem(
                        message = message,
                        usage = if (isLast && !state.busy) state.usage else null,
                        canRegenerate = isLast && !state.busy && message.role == "assistant",
                        onRegenerate = onRegenerate
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: LlmMessage,
    usage: LlmUsage?,
    canRegenerate: Boolean = false,
    onRegenerate: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val copiedToast = tr(R.string.common_copied)
    val copyLabel = tr(R.string.chat_copy_reply)
    if (message.role == "user") {
        SelectionContainer { RequestBubble(message.content) }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (message.reasoning.isNotBlank()) {
            var expanded by remember(message.id) { mutableStateOf(false) }
            val thinkingNow = message.status == LlmMessageStatus.Streaming && message.content.isBlank()
            Row(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (thinkingNow) tr(R.string.chat_thinking_now) else tr(R.string.chat_thinking_process), color = OmniColors.Muted,
                    style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    tint = OmniColors.Muted, modifier = Modifier.padding(start = 2.dp).size(17.dp))
            }
            if (expanded) {
                Row(Modifier.height(IntrinsicSize.Min).padding(bottom = 8.dp)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(OmniColors.Line))
                    Text(message.reasoning, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        if (message.content.isNotBlank()) {
            LlmMarkdown(message.content)
        } else if (message.status == LlmMessageStatus.Streaming && message.reasoning.isBlank()) {
            CircularProgressIndicator(Modifier.size(18.dp), color = OmniColors.Accent, strokeWidth = 2.dp)
        }
        val status = when (message.status) {
            LlmMessageStatus.Complete, LlmMessageStatus.Streaming -> null
            LlmMessageStatus.Cancelled -> tr(R.string.chat_stopped)
            LlmMessageStatus.Interrupted -> tr(R.string.chat_interrupted)
            LlmMessageStatus.MaxTokens -> if (message.reasoning.isNotBlank() && message.content.isBlank()) {
                tr(R.string.chat_think_limit)
            } else tr(R.string.chat_limit_reached)
            LlmMessageStatus.Failed -> tr(R.string.chat_failed)
        }
        status?.let {
            Text(it, color = if (message.status == LlmMessageStatus.Failed) OmniColors.Danger else OmniColors.Dim,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }
        usage?.let {
            Text(usageCaption(it), color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp))
        }
        if (message.status != LlmMessageStatus.Streaming && (message.content.isNotBlank() || canRegenerate)) {
            Row(Modifier.offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
                if (message.content.isNotBlank()) {
                    IconButton(
                        onClick = {
                            runCatching {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, message.content))
                                Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, copyLabel, tint = OmniColors.Dim, modifier = Modifier.size(17.dp))
                    }
                }
                if (canRegenerate) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Refresh, tr(R.string.chat_regenerate), tint = OmniColors.Dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelChip(name: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier.heightIn(min = 40.dp)
    ) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                color = OmniColors.Muted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(Icons.Outlined.ExpandMore, tr(R.string.chat_choose_model), tint = OmniColors.Muted,
                modifier = Modifier.padding(start = 2.dp).size(18.dp))
        }
    }
}

@Composable
private fun ModelPicker(
    state: LlmUiState,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    OmniSheet(tr(R.string.chat_choose_model), onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            state.chatModels.forEach { choice ->
                SettingRow(
                    title = choice.name,
                    detail = choice.detail,
                    modifier = Modifier.alpha(if (choice.ready) 1f else 0.55f),
                    onClick = if (choice.ready) ({ onSelect(choice.id) }) else null,
                    trailing = {
                        RadioButton(
                            selected = choice.id == selectedId,
                            onClick = null,
                            enabled = choice.ready,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = OmniColors.Accent,
                                unselectedColor = OmniColors.Dim,
                                disabledSelectedColor = OmniColors.Dim,
                                disabledUnselectedColor = OmniColors.Dim
                            )
                        )
                    }
                )
                HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
            }
            SettingRow(
                title = tr(R.string.chat_manage_models),
                detail = tr(R.string.chat_manage_models_sub),
                onClick = onManage,
                trailing = { Icon(Icons.Outlined.Memory, null, tint = OmniColors.Muted, modifier = Modifier.size(20.dp)) }
            )
        }
    }
}

@Composable
private fun usageCaption(usage: LlmUsage): String {
    val rate = if (usage.decodeUs > 0) {
        String.format(Locale.getDefault(), " · %.1f tok/s", usage.generatedTokens * 1_000_000.0 / usage.decodeUs)
    } else ""
    val dropped = if (usage.droppedMessages > 0) tr(R.string.chat_dropped_messages, usage.droppedMessages) else ""
    return "$dropped${usage.promptTokens} → ${usage.generatedTokens} tokens$rate"
}

private fun modelSize(bytes: Long): String = String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
