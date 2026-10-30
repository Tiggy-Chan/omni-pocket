package com.tiggy.qwenimage.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.DecisionPhase
import com.tiggy.qwenimage.DecisionViewModel
import com.tiggy.qwenimage.LlmPhase
import com.tiggy.qwenimage.LlmViewModel
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.GenerationBackend
import com.tiggy.qwenimage.data.LlmModelStatus
import java.util.Locale

/** Every model the app can run, in one settings-style list. */
@Composable
fun ModelsPage(
    state: QwenUiState,
    viewModel: QwenViewModel,
    llmViewModel: LlmViewModel,
    decisionViewModel: DecisionViewModel,
    onMenu: (() -> Unit)?
) {
    val llmState by llmViewModel.state.collectAsStateWithLifecycle()
    val decisionState by decisionViewModel.state.collectAsStateWithLifecycle()
    var deleteChat by remember { mutableStateOf<LlmModelStatus?>(null) }
    var deleteDecision by remember { mutableStateOf(false) }
    var sourceOpen by rememberSaveable { mutableStateOf(false) }

    val qwenFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importModels(uri)
    }
    val mossFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importMossModels(uri)
    }
    LaunchedEffect(Unit) {
        viewModel.refreshModelStatus()
        viewModel.refreshMossModelStatus()
        llmViewModel.refreshModels()
        decisionViewModel.refreshModel()
    }

    val modelTask = state.modelTask
    val modelBusy = modelTask.running
    val mossTaskActive = state.activeTask?.backend == GenerationBackend.LocalMoss
    val showModelTask = modelTask.running || modelTask.error != null
    val qwenStatus = state.modelStatus
    val mossStatus = state.mossModelStatus

    WorkspaceScaffold(title = "模型", onMenu = onMenu) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp
                )
            ) {
                item(key = "chat-label") { SectionLabel("对话") }
                items(llmState.models, key = { "chat-${it.spec.id}" }) { status ->
                    val downloading = llmState.workingModelId == status.spec.id && llmState.phase == LlmPhase.Downloading
                    val complete = status.present == status.spec.artifacts.size
                    ModelRow(
                        name = status.spec.name,
                        status = if (status.ready) "已校验 · ${modelSize(status.spec.totalBytes)}"
                        else "${status.message} · ${modelSize(status.spec.totalBytes)}",
                        ready = status.ready,
                        trailing = {
                            when {
                                downloading -> ModelButton("取消", llmViewModel::cancel)
                                status.ready -> ModelButton("删除", { deleteChat = status }, enabled = !llmState.busy, danger = true)
                                complete -> ModelButton("校验", { llmViewModel.verifyModel(status.spec.id) }, enabled = !llmState.busy)
                                else -> ModelButton("下载", { llmViewModel.downloadModel(status.spec.id) }, enabled = !llmState.busy)
                            }
                        },
                        below = if (downloading) ({
                            val progress = llmState.download?.takeIf { it.modelId == status.spec.id }
                            LinearProgressIndicator(
                                progress = { progress?.let { it.completed.toFloat() / it.total.coerceAtLeast(1) } ?: 0f },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = OmniColors.Accent,
                                trackColor = OmniColors.Line,
                                drawStopIndicator = {}
                            )
                            Text(
                                progress?.let { "${modelSize(it.completed)} / ${modelSize(it.total)}" } ?: "正在处理",
                                color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                            )
                        }) else null
                    )
                }

                item(key = "decision-label") { SectionLabel("决策", Modifier.padding(top = 16.dp)) }
                item(key = "decision") {
                    val model = decisionState.model
                    if (model == null) {
                        Text("正在读取模型状态", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        ModelRow(
                            name = model.spec.name,
                            status = if (model.ready) "已校验 · ${modelSize(model.spec.totalBytes)}"
                            else "${model.message} · ${modelSize(model.spec.totalBytes)}",
                            ready = model.ready,
                            trailing = {
                                ModelButton(
                                    if (model.ready) "重新校验" else "校验",
                                    decisionViewModel::verifyModel,
                                    enabled = !decisionState.busy && model.present == model.spec.artifacts.size
                                )
                                if (model.present > 0) {
                                    ModelButton("删除", { deleteDecision = true }, enabled = !decisionState.busy, danger = true)
                                }
                            },
                            below = {
                                if (!model.ready) {
                                    Text("电脑转换后，用 USB 导入", color = OmniColors.Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
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
                                if (decisionState.busy) {
                                    Text(
                                        when (decisionState.phase) {
                                            DecisionPhase.Verifying -> "正在校验"
                                            DecisionPhase.Loading -> "正在加载"
                                            DecisionPhase.Computing -> "正在推理"
                                            DecisionPhase.Idle -> ""
                                        },
                                        color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                decisionState.error?.let {
                                    Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(bottom = 8.dp))
                                }
                            }
                        )
                    }
                }

                item(key = "image-label") { SectionLabel("图像", Modifier.padding(top = 16.dp)) }
                item(key = "qwen") {
                    ModelRow(
                        name = "Qwen 图像",
                        status = qwenStatus?.message ?: "尚未找到模型",
                        ready = qwenStatus?.ready == true,
                        below = {
                            if (showModelTask && !mossTaskActive) TaskLine(modelTask, modifier = Modifier.padding(bottom = 4.dp))
                            ModelButton(
                                "检查推理引擎", viewModel::checkRuntime,
                                enabled = qwenStatus?.ready == true && !modelBusy
                            )
                            HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line, modifier = Modifier.padding(top = 4.dp))
                            SettingRow(
                                title = "来源",
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
                                        ModelButton("下载", viewModel::downloadModels,
                                            enabled = !modelBusy && state.repo.isNotBlank(), modifier = Modifier.weight(1f))
                                        ModelButton("导入", { qwenFolderPicker.launch(null) },
                                            enabled = !modelBusy, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    )
                }
                item(key = "moss") {
                    ModelRow(
                        name = "MOSS 本机",
                        status = mossStatus?.message ?: "尚未找到模型",
                        ready = mossStatus?.ready == true,
                        below = {
                            if (showModelTask && mossTaskActive) TaskLine(modelTask, modifier = Modifier.padding(bottom = 4.dp))
                            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ModelButton("下载", viewModel::downloadMossModels, enabled = !modelBusy,
                                    modifier = Modifier.weight(1f))
                                ModelButton("导入", { mossFolderPicker.launch(null) }, enabled = !modelBusy,
                                    modifier = Modifier.weight(1f))
                                ModelButton(
                                    "校验", viewModel::verifyMossModels,
                                    enabled = !modelBusy && mossStatus?.let { it.total > 0 && it.present == it.total } == true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    deleteChat?.let { status ->
        ConfirmModelDelete(status.spec.name, "释放 ${modelSize(status.spec.totalBytes)} 空间。会话会保留。",
            onConfirm = { llmViewModel.deleteModel(status.spec.id); deleteChat = null },
            onDismiss = { deleteChat = null })
    }
    if (deleteDecision) {
        val model = decisionState.model
        ConfirmModelDelete(model?.spec?.name ?: "决策模型", "释放 ${modelSize(model?.spec?.totalBytes ?: 0L)} 空间。",
            onConfirm = { decisionViewModel.deleteModel(); deleteDecision = false },
            onDismiss = { deleteDecision = false })
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
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(name, color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
                Text(status, color = if (ready) OmniColors.Success else OmniColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (trailing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    content = trailing)
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
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, OmniColors.Line),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) OmniColors.Danger else OmniColors.Accent,
            disabledContentColor = OmniColors.Dim
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
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

private fun modelSize(bytes: Long): String = if (bytes >= 1_000_000_000) {
    String.format(Locale.getDefault(), "%.2f GB", bytes / 1_000_000_000.0)
} else {
    String.format(Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
}
