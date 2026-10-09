package com.tiggy.qwenimage.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.GenerationSettings
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.WorkspaceTab
import java.io.File

@Composable
fun QwenApp(viewModel: QwenViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showError by remember { mutableStateOf<String?>(null) }
    if (state.error != null && showError == null) showError = state.error

    Scaffold(
        topBar = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Qwen Image 2.1", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Q4_K_M · MNN OpenCL · Snapdragon 8 Gen 3", style = MaterialTheme.typography.labelMedium)
            }
        },
        bottomBar = {
            NavigationBar {
                WorkspaceTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Text(tabLabel(tab).take(1)) },
                        label = { Text(tabLabel(tab)) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.tab) {
                    WorkspaceTab.Generate -> GenerateScreen(viewModel, state.settings, state.isBusy, state.progress, state.status, state.resultPath)
                    WorkspaceTab.Queue -> QueueScreen(state.jobs, viewModel::cancelJob)
                WorkspaceTab.Models -> ModelsScreen(viewModel, state.modelStatus, state.repo, state.revision, state.isBusy, state.progress, state.status)
                WorkspaceTab.Settings -> SettingsScreen(viewModel, state.cpuThreads, state.keepModelsLoaded, state.status)
            }
        }
    }

    showError?.let { message ->
        AlertDialog(
            onDismissRequest = { showError = null; viewModel.clearError() },
            title = { Text("提示") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { showError = null; viewModel.clearError() }) { Text("确定") } }
        )
    }
}

@Composable
private fun GenerateScreen(
    viewModel: QwenViewModel,
    settings: GenerationSettings,
    busy: Boolean,
    progress: Int,
    status: String,
    resultPath: String?
) {
    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setInput(uri)
    }
    val input2Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setInput(uri, second = true)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(settings.mode == GenerationMode.TextToImage, { viewModel.setMode(GenerationMode.TextToImage) }, label = { Text("文生图") })
            FilterChip(settings.mode == GenerationMode.ImageEdit, { viewModel.setMode(GenerationMode.ImageEdit) }, label = { Text("图片编辑") })
        }
        OutlinedTextField(
            value = settings.prompt,
            onValueChange = viewModel::setPrompt,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("Prompt") },
            placeholder = { Text("A cinematic portrait with precise typography…") }
        )
        if (settings.mode == GenerationMode.ImageEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { inputPicker.launch(arrayOf("image/*")) }) { Text("选择参考图") }
                OutlinedButton(onClick = { input2Picker.launch(arrayOf("image/*")) }) { Text("第二张参考图") }
            }
            Text(
                if (settings.inputUri == null) "尚未选择参考图" else "参考图已选择" + if (settings.inputUri2 != null) "（双图）" else "",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text("输出尺寸：${settings.width} × ${settings.height}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(320 to 320, 384 to 384, 512 to 512).forEach { (width, height) ->
                FilterChip(
                    selected = settings.width == width,
                    onClick = { viewModel.setSize(width, height) },
                    label = { Text("${width}²") }
                )
            }
        }
        OutlinedTextField(
            value = settings.steps.toString(),
            onValueChange = { value -> value.toIntOrNull()?.let(viewModel::setSteps) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Steps（Turbo 固定 6）") }
        )
        OutlinedTextField(
            value = settings.seed.toString(),
            onValueChange = { value -> value.toIntOrNull()?.let(viewModel::setSeed) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Seed") }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(settings.turbo, viewModel::setTurbo)
            Text("Turbo 6 步快速档")
            Spacer(Modifier.width(12.dp))
            Checkbox(settings.useTinyVae, viewModel::setTinyVae)
            Text("Tiny VAE")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(settings.transparent, viewModel::setTransparent)
            Text("透明 PNG（按提示词输出 RGBA）")
        }
        if (busy) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)
        Button(onClick = viewModel::enqueue, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (settings.mode == GenerationMode.TextToImage) "加入生成队列" else "加入编辑队列")
        }
        resultPath?.let { path ->
            val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
            if (bitmap != null) {
                Text("最新结果", style = MaterialTheme.typography.titleMedium)
                Image(bitmap.asImageBitmap(), "生成结果", Modifier.fillMaxWidth().height(300.dp))
                Text(File(path).absolutePath, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun QueueScreen(jobs: List<com.tiggy.qwenimage.data.GenerationJob>, cancel: (String) -> Unit) {
    if (jobs.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("队列为空")
            Text("在生成工作台提交一个任务即可开始", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(jobs, key = { it.id }) { job ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(job.settings.prompt, maxLines = 2, style = MaterialTheme.typography.bodyLarge)
                    Text("${job.settings.mode} · ${job.settings.width}×${job.settings.height} · seed ${job.settings.seed}", style = MaterialTheme.typography.labelSmall)
                    Text(job.state, style = MaterialTheme.typography.labelMedium)
                    if (job.state == "running") LinearProgressIndicator(progress = { job.progress / 100f }, Modifier.fillMaxWidth())
                    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    job.outputPath?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    if (job.state == "queued") TextButton(onClick = { cancel(job.id) }) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun ModelsScreen(
    viewModel: QwenViewModel,
    status: ModelStatus?,
    repo: String,
    revision: String,
    busy: Boolean,
    progress: Int,
    message: String
) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importModels(uri)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型管理", style = MaterialTheme.typography.headlineSmall)
        Text("手机运行的是转换后的 MNN 文件。原始 abenzerps GGUF 需要先在电脑转换。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(repo, viewModel::setRepo, Modifier.fillMaxWidth(), label = { Text("Hugging Face MNN 仓库，例如 user/repo") })
        OutlinedTextField(revision, viewModel::setRevision, Modifier.fillMaxWidth(), label = { Text("Revision") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::downloadModels, enabled = !busy && repo.isNotBlank()) { Text("断点下载并校验") }
            OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy) { Text("导入本地目录") }
        }
        if (busy) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        Text(message, style = MaterialTheme.typography.bodySmall)
        status?.let {
            Text("目录：${it.directory.absolutePath}", style = MaterialTheme.typography.labelSmall)
            Text("${it.message} · ${if (it.ready) "可生成" else "未完成"}")
        }
        Text("推荐配置：Q4_K_M DiT int4 + Qwen3-VL int4 + Tiny VAE FP16。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(viewModel: QwenViewModel, threads: Int, keepLoaded: Boolean, status: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("性能与诊断", style = MaterialTheme.typography.headlineSmall)
        Text("CPU 线程：$threads")
        Slider(value = threads.toFloat(), onValueChange = { viewModel.setCpuThreads(it.toInt()) }, valueRange = 2f..8f, steps = 5)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(keepLoaded, viewModel::setKeepModelsLoaded)
            Text("保持模型常驻（24GB 内存推荐）")
        }
        Text("当前状态：$status", style = MaterialTheme.typography.bodySmall)
        Text("引擎：MNN OpenCL；文本编码器 CPU；Tiny VAE GPU。", style = MaterialTheme.typography.bodySmall)
        Text("模型许可：Qwen Research License。请在发布前检查权重和第三方组件许可。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun tabLabel(tab: WorkspaceTab): String = when (tab) {
    WorkspaceTab.Generate -> "生成"
    WorkspaceTab.Queue -> "队列"
    WorkspaceTab.Models -> "模型"
    WorkspaceTab.Settings -> "设置"
}
