package com.tiggy.qwenimage.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.MossModelStatus
import com.tiggy.qwenimage.data.TaskUiState

@Composable
fun ModelsScreen(
    viewModel: QwenViewModel,
    status: ModelStatus?,
    repo: String,
    revision: String,
    task: TaskUiState,
    mossStatus: MossModelStatus?,
    mossTaskActive: Boolean
) {
    val qwenFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importModels(uri)
    }
    val mossFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importMossModels(uri)
    }
    LaunchedEffect(Unit) {
        viewModel.refreshModelStatus()
        viewModel.refreshMossModelStatus()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ContentCard(title = "Qwen 模型") {
                Text(status?.message ?: "尚未找到模型", color = if (status?.ready == true) OmniColors.Mint else OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                if (!mossTaskActive) OmniTaskStatus(task)
                OutlinedButton(
                    onClick = viewModel::checkRuntime,
                    enabled = status?.ready == true && !task.running,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("检查推理引擎") }
            }
        }
        item {
            ContentCard(title = "MOSS 模型") {
                Text(
                    mossStatus?.message ?: "尚未找到模型",
                    color = if (mossStatus?.ready == true) OmniColors.Mint else OmniColors.Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (mossTaskActive) OmniTaskStatus(task)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::downloadMossModels,
                        enabled = !task.running,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("下载") }
                    OutlinedButton(
                        onClick = { mossFolderPicker.launch(null) },
                        enabled = !task.running,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("导入") }
                }
                OutlinedButton(
                    onClick = viewModel::verifyMossModels,
                    enabled = !task.running && mossStatus?.let { it.total > 0 && it.present == it.total } == true,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("校验模型") }
            }
        }
        item {
            ContentCard(title = "模型来源") {
                OmniTextField(
                    value = repo,
                    onValueChange = viewModel::setRepo,
                    label = "Hugging Face owner/repo",
                    placeholder = "包含 manifest.json 的转换仓库",
                    modifier = Modifier.fillMaxWidth()
                )
                OmniTextField(
                    value = revision,
                    onValueChange = viewModel::setRevision,
                    label = "Revision",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::downloadModels,
                        enabled = !task.running && repo.isNotBlank(),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("下载") }
                    OutlinedButton(
                        onClick = { qwenFolderPicker.launch(null) },
                        enabled = !task.running,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("导入") }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: QwenViewModel, threads: Int, keepLoaded: Boolean, status: String, modelReady: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ContentCard(title = "性能") {
                Text("CPU 线程：$threads", color = OmniColors.Ink, style = MaterialTheme.typography.bodyMedium)
                Slider(value = threads.toFloat(), onValueChange = { viewModel.setCpuThreads(it.toInt()) }, valueRange = 2f..8f, steps = 5)
                Row(
                    Modifier.fillMaxWidth().height(48.dp).toggleable(
                        value = keepLoaded,
                        role = Role.Checkbox,
                        onValueChange = viewModel::setKeepModelsLoaded
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = keepLoaded, onCheckedChange = null)
                    Text("空闲时保持 Qwen 模型常驻", color = OmniColors.Ink, style = MaterialTheme.typography.bodyMedium)
                }
                Text(status, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ContentCard(title = "运行环境") {
                Text("Qwen：MNN · OpenCL", color = OmniColors.Muted)
                Text("视频：Wan CLI · OpenCL / CPU", color = OmniColors.Muted)
                Text("音乐：YuE2 工作站", color = OmniColors.Muted)
                Text("MOSS：本机 / ComfyUI 工作站", color = OmniColors.Muted)
                Text(if (modelReady) "Qwen 模型已校验" else "Qwen 模型未校验", color = if (modelReady) OmniColors.Mint else OmniColors.Dim)
            }
        }
    }
}
