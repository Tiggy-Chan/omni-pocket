package com.tiggy.qwenimage.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.LlmPhase
import com.tiggy.qwenimage.LlmUiState
import com.tiggy.qwenimage.LlmUsage
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.data.LlmMessage
import com.tiggy.qwenimage.data.LlmMessageStatus
import java.util.Locale

@Composable
fun LlmChatScreen(viewModel: LlmViewModel, onMenu: (() -> Unit)?, onModels: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val conversation = state.conversation
    val model = state.models.firstOrNull { it.spec.id == conversation?.modelId }
    val modelName = model?.spec?.name ?: "Qwen3.5"
    val thinking = conversation?.thinking == true
    val loading = state.phase == LlmPhase.Loading
    val running = loading || state.phase == LlmPhase.Generating

    state.alert?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearAlert,
            title = { Text("需要处理") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearAlert) { Text("知道了") } },
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
        title = conversation?.title ?: "对话",
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
                        Icons.Outlined.Psychology, "深度思考",
                        tint = (if (thinking) OmniColors.Accent else OmniColors.Muted).copy(alpha = if (state.busy) 0.5f else 1f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        composer = { haze ->
            val status: (@Composable () -> Unit)? = if (loading || state.error != null) ({
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (loading) Text("正在加载模型…", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall) }
                }
            }) else null
            OmniComposer(
                hazeState = haze,
                value = state.draft,
                onValueChange = viewModel::updateDraft,
                placeholder = "发消息",
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
        Transcript(state, padding, onModels)
    }
}

@Composable
private fun Transcript(state: LlmUiState, padding: PaddingValues, onModels: () -> Unit) {
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
                        Text("开始对话", color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium)
                        if (state.ready && !state.modelReady) {
                            Text("先在模型页下载 Qwen3.5", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onModels) { Text("打开模型") }
                        }
                    }
                }
            }
        } else {
            val lastId = messages.last().id
            items(messages, key = { it.id }) { message ->
                ReadingColumn {
                    MessageItem(message, usage = if (message.id == lastId && !state.busy) state.usage else null)
                }
            }
        }
    }
}

@Composable
private fun MessageItem(message: LlmMessage, usage: LlmUsage?) {
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
                Text(if (thinkingNow) "正在思考" else "思考过程", color = OmniColors.Muted,
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
            LlmMessageStatus.Cancelled -> "已停止"
            LlmMessageStatus.Interrupted -> "已中断"
            LlmMessageStatus.MaxTokens -> if (message.reasoning.isNotBlank() && message.content.isBlank()) {
                "思考已达上限，未生成答案"
            } else "已到输出上限"
            LlmMessageStatus.Failed -> "生成失败"
        }
        status?.let {
            Text(it, color = if (message.status == LlmMessageStatus.Failed) OmniColors.Danger else OmniColors.Dim,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }
        usage?.let {
            Text(usageCaption(it), color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp))
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
            Text(name, color = OmniColors.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, "选择模型", tint = OmniColors.Muted,
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
    OmniSheet("选择模型", onDismiss) {
        Column {
            state.models.forEach { status ->
                SettingRow(
                    title = status.spec.name,
                    detail = if (status.ready) "已就绪" else "${status.message} · ${modelSize(status.spec.totalBytes)}",
                    modifier = Modifier.alpha(if (status.ready) 1f else 0.55f),
                    onClick = if (status.ready) ({ onSelect(status.spec.id) }) else null,
                    trailing = {
                        RadioButton(
                            selected = status.spec.id == selectedId,
                            onClick = null,
                            enabled = status.ready,
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
                title = "管理模型",
                onClick = onManage,
                trailing = { Icon(Icons.Outlined.Memory, null, tint = OmniColors.Muted, modifier = Modifier.size(20.dp)) }
            )
        }
    }
}

private fun usageCaption(usage: LlmUsage): String {
    val rate = if (usage.decodeUs > 0) {
        String.format(Locale.getDefault(), " · %.1f tok/s", usage.generatedTokens * 1_000_000.0 / usage.decodeUs)
    } else ""
    return (if (usage.droppedMessages > 0) "省略 ${usage.droppedMessages} 条旧消息 · " else "") +
        "${usage.promptTokens} → ${usage.generatedTokens} tokens$rate"
}

private fun modelSize(bytes: Long): String = String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
