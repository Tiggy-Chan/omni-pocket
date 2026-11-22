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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionDraft
import com.tiggy.qwenimage.DecisionPhase
import com.tiggy.qwenimage.DecisionUiState
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.R
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
    var pickingModel by remember { mutableStateOf(false) }
    val activeModelName = state.selectedModelChoice?.name ?: "Elephant-1 9B"

    if (pickingModel) {
        DecisionModelPicker(
            state = state,
            onSelect = { id -> viewModel.selectModel(id); pickingModel = false },
            onManage = { pickingModel = false; onModels() },
            onDismiss = { pickingModel = false }
        )
    }

    WorkspaceScaffold(
        title = tr(R.string.nav_decision),
        subtitle = tr(R.string.common_on_device_tag, activeModelName) + if (state.loaded) tr(R.string.decision_loaded_suffix) else "",
        onMenu = onMenu,
        actions = {
            TextButton(onClick = viewModel::showExample, enabled = !state.busy) { Text(tr(R.string.decision_example)) }
        },
        composer = { haze ->
            ActionArea(
                state = state,
                activeModelName = activeModelName,
                haze = haze,
                onPickModel = { pickingModel = true },
                onRun = viewModel::run,
                onCancel = viewModel::cancel,
                onModels = onModels
            )
        }
    ) { padding ->
        DecisionForm(state, viewModel, padding)
    }
}

@Composable
private fun DecisionModelPicker(
    state: DecisionUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    OmniSheet(tr(R.string.decision_choose_model), onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            state.decisionModels.forEach { choice ->
                SettingRow(
                    title = choice.name,
                    detail = choice.localizedDetail(),
                    modifier = Modifier.alpha(if (choice.ready) 1f else 0.55f),
                    onClick = if (choice.ready) ({ onSelect(choice.id) }) else null,
                    trailing = {
                        RadioButton(
                            selected = choice.id == state.selectedModelId,
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
                detail = tr(R.string.decision_discover_note),
                onClick = onManage,
                trailing = { Icon(Icons.Outlined.Memory, null, tint = OmniColors.Muted, modifier = Modifier.size(20.dp)) }
            )
        }
    }
}

@Composable
private fun DecisionForm(state: DecisionUiState, viewModel: DecisionViewModel, padding: PaddingValues) {
    val busy = state.busy
    val textTabLabel = tr(R.string.decision_text_tab)
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
                SectionLabel(tr(R.string.decision_state))
                OmniTextField(
                    value = state.stateText,
                    onValueChange = viewModel::updateState,
                    label = if (state.jsonState) tr(R.string.decision_json_state) else tr(R.string.decision_text_state),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = tr(R.string.decision_state_hint),
                    minLines = 4,
                    maxLines = 8,
                    enabled = !busy
                )
                Column(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                    OmniTabs(
                        items = listOf(false, true),
                        selected = state.jsonState,
                        label = { if (it) "JSON" else textTabLabel },
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
                    Text(tr(R.string.decision_add_question), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        state.result?.let { result ->
            item(key = "summary") {
                ReadingColumn {
                    Text(
                        tr(R.string.decision_shared_prefix, result.prefixTokens, seconds(result.prefixPrefillUs + result.branchPrefillUs)),
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
    val choiceLabel = tr(R.string.decision_choice)
    val yesNoLabel = tr(R.string.decision_yesno)
    val scoreLabel = tr(R.string.decision_score)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.decision_question_n, index + 1), color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f))
            if (removable) {
                IconButton(onClick = { viewModel.removeQuestion(question.id) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, tr(R.string.decision_delete_question), tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OmniTabs(
                items = DecisionKind.entries,
                selected = question.kind,
                label = {
                    when (it) {
                        DecisionKind.Choice -> choiceLabel
                        DecisionKind.Noul -> yesNoLabel
                        DecisionKind.Score -> scoreLabel
                    }
                },
                enabled = !busy
            ) { viewModel.changeKind(question.id, it) }
            OmniTextField(
                value = question.instructions,
                onValueChange = { viewModel.updateInstructions(question.id, it) },
                label = when (question.kind) {
                    DecisionKind.Choice -> tr(R.string.decision_question)
                    DecisionKind.Noul -> yesNoLabel
                    DecisionKind.Score -> tr(R.string.decision_score_question)
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
                        label = tr(R.string.decision_option_n, slotLabels.getOrNull(index) ?: (index + 1).toString()),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    )
                }
                OmniTextField(
                    value = option.description,
                    onValueChange = { viewModel.updateOptionDescription(question.id, index, it) },
                    label = if (score) tr(R.string.decision_level_n, index) else tr(R.string.decision_notes),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (score) tr(R.string.decision_level_hint) else tr(R.string.decision_optional),
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
                    Icon(Icons.Outlined.DeleteOutline, tr(R.string.decision_delete_option), tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    val limit = if (score) MAX_SCORE_LEVELS else MAX_CHOICE_OPTIONS
    if (question.options.size < limit) {
        TextButton(onClick = { viewModel.addOption(question.id) }, enabled = !busy) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(17.dp))
            Text(if (score) tr(R.string.decision_add_level) else tr(R.string.decision_add_option), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun AnswerBlock(answer: DecisionAnswer) {
    val best = answer.options.getOrNull(answer.bestIndex)
    val yesLabel = tr(R.string.decision_yes)
    val noLabel = tr(R.string.decision_no)
    val headline = when (answer.kind) {
        DecisionKind.Noul -> best?.let { (if (answer.bestIndex == 0) "$yesLabel " else "$noLabel ") + percent(it.probability) }
        DecisionKind.Choice -> best?.name
        DecisionKind.Score -> tr(R.string.decision_score_result, decimal(answer.score ?: 0.0), answer.options.lastIndex)
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            if (headline != null) {
                Text(headline, color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            answer.confidence?.let {
                Text(tr(R.string.decision_confidence, percent(it)), color = OmniColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        answer.options.forEachIndexed { index, option ->
            val name = when (answer.kind) {
                DecisionKind.Noul -> if (index == 0) yesLabel else noLabel
                DecisionKind.Choice -> option.name
                DecisionKind.Score -> tr(R.string.decision_level_n, index)
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
            Text(tr(R.string.decision_chunks_mass, answer.chunks, percent(answer.allowedMass)),
                color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ActionArea(
    state: DecisionUiState,
    activeModelName: String,
    haze: HazeState,
    onPickModel: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onModels: () -> Unit
) {
    OverlayGlass(haze, Modifier.fillMaxWidth(), radius = 22.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = onPickModel,
                    enabled = !state.busy,
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    modifier = Modifier.heightIn(min = 36.dp)
                ) {
                    Row(Modifier.padding(start = 6.dp, end = 4.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            activeModelName,
                            color = OmniColors.Muted,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            Icons.Outlined.ExpandMore,
                            tr(R.string.decision_choose_model),
                            tint = OmniColors.Muted,
                            modifier = Modifier.padding(start = 2.dp).size(18.dp)
                        )
                    }
                }
            }
            if (state.busy) {
                Text(
                    when (state.phase) {
                        DecisionPhase.Verifying -> tr(R.string.decision_verifying)
                        DecisionPhase.Loading -> tr(R.string.decision_loading_model, activeModelName)
                        DecisionPhase.Computing -> tr(R.string.decision_evaluating, state.progress, state.total)
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
                state.busy -> OmniPrimaryButton(tr(R.string.common_stop), onClick = onCancel)
                !state.modelReady -> OmniPrimaryButton(tr(R.string.decision_prepare_model), onClick = onModels, enabled = state.ready)
                else -> OmniPrimaryButton(tr(R.string.decision_run), onClick = onRun, enabled = state.ready && state.stateText.isNotBlank())
            }
        }
    }
}

private fun percent(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value * 100)
private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
private fun seconds(value: Long): String = String.format(Locale.getDefault(), "%.1f s", value / 1_000_000.0)
