package com.tiggy.qwenimage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionDraft
import com.tiggy.qwenimage.DecisionPhase
import com.tiggy.qwenimage.DecisionUiState
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.data.DecisionAnswer
import com.tiggy.qwenimage.data.DecisionKind
import com.tiggy.qwenimage.data.decisionSlotLabels
import dev.chrisbanes.haze.HazeState
import java.util.Locale

private const val MAX_QUESTIONS = 32
private const val MAX_CHOICE_OPTIONS = 255
private const val MAX_SCORE_LEVELS = 10

private val slotLabels: List<String> by lazy { decisionSlotLabels() }

@Composable
fun DecisionScreen(viewModel: DecisionViewModel, onMenu: (() -> Unit)?, onModels: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkspaceScaffold(
        title = "决策",
        subtitle = "Elephant-1 · 本机" + if (state.loaded) " · 已加载" else "",
        onMenu = onMenu,
        actions = {
            TextButton(onClick = viewModel::showExample, enabled = !state.busy) { Text("示例") }
        },
        composer = { haze ->
            ActionArea(state, haze, onRun = viewModel::run, onCancel = viewModel::cancel, onModels = onModels)
        }
    ) { padding ->
        DecisionForm(state, viewModel, padding)
    }
}

@Composable
private fun DecisionForm(state: DecisionUiState, viewModel: DecisionViewModel, padding: PaddingValues) {
    val busy = state.busy
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp
        )
    ) {
        item(key = "state") {
            ReadingColumn {
                SectionLabel("状态")
                OmniTextField(
                    value = state.stateText,
                    onValueChange = viewModel::updateState,
                    label = if (state.jsonState) "JSON 状态" else "文本状态",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "事实、上下文或 JSON",
                    minLines = 4,
                    maxLines = 8,
                    enabled = !busy
                )
                Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                    OmniTabs(
                        items = listOf(false, true),
                        selected = state.jsonState,
                        label = { if (it) "JSON" else "文本" },
                        enabled = !busy
                    ) { viewModel.setJsonState(it) }
                }
            }
        }
        itemsIndexed(state.questions, key = { _, question -> question.id }) { index, question ->
            ReadingColumn {
                QuestionBlock(
                    index = index,
                    question = question,
                    answer = state.result?.answers?.firstOrNull { it.questionId == question.id },
                    removable = state.questions.size > 1,
                    busy = busy,
                    viewModel = viewModel
                )
            }
        }
        item(key = "add") {
            ReadingColumn {
                HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
                TextButton(
                    onClick = viewModel::addQuestion,
                    enabled = !busy && state.questions.size < MAX_QUESTIONS,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Text("添加问题", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        state.result?.let { result ->
            item(key = "summary") {
                ReadingColumn {
                    Text(
                        "共享前缀 ${result.prefixTokens} tokens · ${seconds(result.prefixPrefillUs + result.branchPrefillUs)}",
                        color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    index: Int,
    question: DecisionDraft,
    answer: DecisionAnswer?,
    removable: Boolean,
    busy: Boolean,
    viewModel: DecisionViewModel
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("问题 ${index + 1}", color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f))
            if (removable) {
                IconButton(onClick = { viewModel.removeQuestion(question.id) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, "删除问题", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OmniTabs(
                items = DecisionKind.entries,
                selected = question.kind,
                label = { kindLabel(it) },
                enabled = !busy
            ) { viewModel.changeKind(question.id, it) }
            OmniTextField(
                value = question.instructions,
                onValueChange = { viewModel.updateInstructions(question.id, it) },
                label = when (question.kind) {
                    DecisionKind.Choice -> "问题"
                    DecisionKind.Noul -> "判断"
                    DecisionKind.Score -> "评分问题"
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = when (question.kind) {
                    DecisionKind.Choice -> "Which option applies to the state?"
                    DecisionKind.Noul -> "Is the statement true, or is the answer to the question yes?"
                    DecisionKind.Score -> "Which level describes the state?"
                },
                maxLines = 3,
                enabled = !busy
            )
            if (question.kind != DecisionKind.Noul) OptionsEditor(question, busy, viewModel)
            answer?.let { AnswerBlock(it) }
        }
    }
}

@Composable
private fun OptionsEditor(question: DecisionDraft, busy: Boolean, viewModel: DecisionViewModel) {
    val score = question.kind == DecisionKind.Score
    question.options.forEachIndexed { index, option ->
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!score) {
                    OmniTextField(
                        value = option.name,
                        onValueChange = { viewModel.updateOptionName(question.id, index, it) },
                        label = "选项 ${slotLabels.getOrNull(index) ?: index + 1}",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    )
                }
                OmniTextField(
                    value = option.description,
                    onValueChange = { viewModel.updateOptionDescription(question.id, index, it) },
                    label = if (score) "等级 $index" else "说明",
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (score) "描述该等级" else "可选",
                    maxLines = 3,
                    enabled = !busy
                )
            }
            if (question.options.size > 1) {
                IconButton(
                    onClick = { viewModel.removeOption(question.id, index) },
                    enabled = !busy,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.DeleteOutline, "删除选项", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    val limit = if (score) MAX_SCORE_LEVELS else MAX_CHOICE_OPTIONS
    if (question.options.size < limit) {
        TextButton(onClick = { viewModel.addOption(question.id) }, enabled = !busy) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(17.dp))
            Text(if (score) "添加等级" else "添加选项", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun AnswerBlock(answer: DecisionAnswer) {
    val best = answer.options.getOrNull(answer.bestIndex)
    val headline = when (answer.kind) {
        DecisionKind.Noul -> best?.let { (if (answer.bestIndex == 0) "是 " else "否 ") + percent(it.probability) }
        DecisionKind.Choice -> best?.name
        DecisionKind.Score -> "评分 ${decimal(answer.score ?: 0.0)} / ${answer.options.lastIndex}"
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            if (headline != null) {
                Text(headline, color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            answer.confidence?.let {
                Text("置信 ${percent(it)}", color = OmniColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        answer.options.forEachIndexed { index, option ->
            val name = when (answer.kind) {
                DecisionKind.Noul -> if (index == 0) "是" else "否"
                DecisionKind.Choice -> option.name
                DecisionKind.Score -> "等级 $index"
            }
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = OmniColors.Ink, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == answer.bestIndex) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(percent(option.probability), color = OmniColors.Muted, style = MaterialTheme.typography.labelMedium)
                }
                if (answer.kind != DecisionKind.Noul && option.description.isNotBlank() && option.description != option.name) {
                    Text(option.description, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                LinearProgressIndicator(
                    progress = { option.probability.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                    color = OmniColors.Accent,
                    trackColor = OmniColors.Line,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
        }
        if (answer.chunks > 1) {
            Text("${answer.chunks} 个分块 · 标签概率质量 ${percent(answer.allowedMass)}",
                color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ActionArea(
    state: DecisionUiState,
    haze: HazeState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onModels: () -> Unit
) {
    OverlayGlass(haze, Modifier.fillMaxWidth(), radius = 22.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) {
                Text(
                    when (state.phase) {
                        DecisionPhase.Verifying -> "正在校验模型"
                        DecisionPhase.Loading -> "正在加载 9B 模型"
                        DecisionPhase.Computing -> "正在计算 ${state.progress}/${state.total}"
                        DecisionPhase.Idle -> ""
                    },
                    color = OmniColors.Muted, style = MaterialTheme.typography.labelMedium
                )
                if (state.phase == DecisionPhase.Computing && state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.progress.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = OmniColors.Accent, trackColor = OmniColors.Line, drawStopIndicator = {}
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = OmniColors.Accent, trackColor = OmniColors.Line
                    )
                }
            }
            state.error?.let {
                Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
            when {
                state.busy -> OmniPrimaryButton("停止", onClick = onCancel)
                !state.modelReady -> OmniPrimaryButton("准备模型", onClick = onModels, enabled = state.ready)
                else -> OmniPrimaryButton("开始决策", onClick = onRun, enabled = state.ready && state.stateText.isNotBlank())
            }
        }
    }
}

private fun kindLabel(kind: DecisionKind) = when (kind) {
    DecisionKind.Choice -> "选择"
    DecisionKind.Noul -> "判断"
    DecisionKind.Score -> "评分"
}

private fun percent(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value * 100)
private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
private fun seconds(value: Long): String = String.format(Locale.getDefault(), "%.1f s", value / 1_000_000.0)
