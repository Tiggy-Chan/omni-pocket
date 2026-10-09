package com.tiggy.qwenimage.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.DEFAULT_NEGATIVE_PROMPT
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.GenerationSettings
import com.tiggy.qwenimage.data.ModelStatus
import com.tiggy.qwenimage.data.WorkspaceTab
import java.io.File

private val Ink = Color(0xFFF5F3FF)
private val Muted = Color(0xFFA7A7BB)
private val Violet = Color(0xFFB7A1FF)
private val Mint = Color(0xFF85E6D1)
private val Glass = Color(0xB8161928)
private val GlassStroke = Color(0x32FFFFFF)

@Composable
fun QwenApp(viewModel: QwenViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showError by remember { mutableStateOf<String?>(null) }
    if (state.error != null && showError == null) showError = state.error
    val background = Brush.verticalGradient(listOf(Color(0xFF11152A), Color(0xFF090A12), Color(0xFF151126)))

    Box(Modifier.fillMaxSize().background(background)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { TopBar(state.isBusy, state.modelStatus?.ready == true) },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    containerColor = Glass,
                    tonalElevation = 0.dp
                ) {
                    WorkspaceTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            icon = { Icon(tabIcon(tab), tabLabel(tab), Modifier.size(21.dp)) },
                            label = { Text(tabLabel(tab), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (state.tab) {
                    WorkspaceTab.Generate -> GenerateScreen(viewModel, state.settings, state.isBusy, state.progress, state.status, state.resultPath, state.modelStatus?.manifest?.features?.contains("turbo") == true, state.modelStatus?.ready == true)
                    WorkspaceTab.Queue -> QueueScreen(state.jobs, viewModel::cancelJob)
                    WorkspaceTab.Models -> ModelsScreen(viewModel, state.modelStatus, state.repo, state.revision, state.isBusy, state.progress, state.status)
                    WorkspaceTab.Settings -> SettingsScreen(viewModel, state.cpuThreads, state.keepModelsLoaded, state.status)
                }
            }
        }
    }

    showError?.let { message ->
        AlertDialog(
            onDismissRequest = { showError = null; viewModel.clearError() },
            title = { Text("需要处理") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { showError = null; viewModel.clearError() }) { Text("知道了") } }
        )
    }
}

@Composable
private fun TopBar(busy: Boolean, modelReady: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(42.dp), shape = RoundedCornerShape(15.dp), color = Color(0xFF7C67D8)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Qwen Image", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("2.1  ·  本地生成工作台", color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        Surface(shape = RoundedCornerShape(50), color = Color(0x331E2439), modifier = Modifier.border(1.dp, GlassStroke, RoundedCornerShape(50))) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (busy) Color(0xFFFFC36B) else if (modelReady) Mint else Color(0xFFFF889D), RoundedCornerShape(50)))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "处理中" else if (modelReady) "就绪" else "待配置", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun GenerateScreen(
    viewModel: QwenViewModel,
    settings: GenerationSettings,
    busy: Boolean,
    progress: Int,
    status: String,
    resultPath: String?,
    turboAvailable: Boolean,
    modelReady: Boolean
) {
    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.setInput(uri) }
    val input2Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.setInput(uri, second = true) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(settings.mode == GenerationMode.TextToImage, "文生图", Icons.Outlined.AutoAwesome) { viewModel.setMode(GenerationMode.TextToImage) }
                ModeChip(settings.mode == GenerationMode.ImageEdit, "智能编辑", Icons.Outlined.Image) { viewModel.setMode(GenerationMode.ImageEdit) }
            }
        }

        GlassCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Violet, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("描述你想看到的画面", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("Q4_K_M", color = Mint, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.prompt,
                onValueChange = viewModel::setPrompt,
                modifier = Modifier.fillMaxWidth(), minLines = 4,
                label = { Text("正向提示词") },
                placeholder = { Text("A cinematic portrait with precise typography…") },
                shape = RoundedCornerShape(18.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.negativePrompt,
                onValueChange = viewModel::setNegativePrompt,
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("负向提示词") },
                placeholder = { Text("可选：deformed anatomy, blurry, watermark…") },
                shape = RoundedCornerShape(18.dp)
            )
            Text("手机 MNN 路径以 prompt-only 为主，负向内容会转为 Avoid 指令。", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PresetChip("人像", Icons.Outlined.Image) { viewModel.setPrompt("A refined adult portrait, soft cinematic light, natural face, detailed fabric, elegant composition") }
            PresetChip("透明贴纸", Icons.Outlined.AutoAwesome) {
                viewModel.setTransparent(true); viewModel.setPrompt("A cute illustrated character sticker, clean silhouette, vibrant pastel colors")
            }
            PresetChip("16:9", Icons.Outlined.Tune) { viewModel.setSize(512, 288) }
        }

        if (settings.mode == GenerationMode.ImageEdit) {
            GlassCard(accent = Mint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Mint, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("参考图", color = Ink, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(if (settings.inputUri2 != null) "双图" else "单图", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { inputPicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (settings.inputUri == null) "选择图片" else "更换图片")
                    }
                    IconButton(onClick = { input2Picker.launch(arrayOf("image/*")) }) { Icon(Icons.Outlined.AddPhotoAlternate, "第二张参考图") }
                }
                Text(if (settings.inputUri == null) "未选择参考图" else if (settings.inputUri2 == null) "已选择 1 张参考图" else "已选择 2 张参考图", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }

        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip("画幅", "${settings.width}×${settings.height}", Modifier.weight(1f))
                MetricChip("步数", if (settings.turbo) "6" else settings.steps.toString(), Modifier.weight(1f))
                MetricChip("种子", settings.seed.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(320 to 320, 384 to 384, 512 to 512).forEach { (width, height) ->
                    FilterChip(selected = settings.width == width && settings.height == height, onClick = { viewModel.setSize(width, height) }, label = { Text("${width}²") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Checkbox(settings.turbo, viewModel::setTurbo, enabled = turboAvailable)
                Text(if (turboAvailable) "Turbo" else "Turbo 未安装", color = if (turboAvailable) Ink else Muted)
                Spacer(Modifier.width(8.dp))
                Checkbox(settings.useTinyVae, viewModel::setTinyVae)
                Text("Tiny VAE", color = Ink)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "更多设置", tint = Muted) }
            }
            if (expanded) {
                OutlinedTextField(settings.steps.toString(), { it.toIntOrNull()?.let(viewModel::setSteps) }, Modifier.fillMaxWidth(), label = { Text("Steps") }, shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(settings.seed.toString(), { it.toIntOrNull()?.let(viewModel::setSeed) }, Modifier.fillMaxWidth(), label = { Text("Seed") }, shape = RoundedCornerShape(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(settings.transparent, viewModel::setTransparent); Text("透明 RGBA 输出", color = Ink) }
            }
        }

        if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Violet, trackColor = Color(0x334A4F6B))
        Text(status, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(
            onClick = viewModel::enqueue,
            enabled = !busy && modelReady,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C67D8), contentColor = Color.White)
        ) {
            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (settings.mode == GenerationMode.TextToImage) "开始生成" else "开始编辑", fontWeight = FontWeight.SemiBold)
        }

        resultPath?.let { path ->
            val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
            if (bitmap != null) GlassCard { Text("最新作品", color = Ink, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Image(bitmap.asImageBitmap(), "生成结果", Modifier.fillMaxWidth().height(300.dp)); Text(File(path).name, color = Muted, style = MaterialTheme.typography.labelSmall) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun QueueScreen(jobs: List<com.tiggy.qwenimage.data.GenerationJob>, cancel: (String) -> Unit) {
    if (jobs.isEmpty()) return EmptyState(Icons.Outlined.Queue, "队列还是空的", "提交一个 prompt，作品会在这里排队")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(jobs, key = { it.id }) { job ->
            GlassCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(if (job.state == "done") Icons.Outlined.CheckCircle else Icons.Outlined.AutoAwesome, null, tint = if (job.state == "done") Mint else Violet)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(job.settings.prompt, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${job.settings.mode} · ${job.settings.width}×${job.settings.height} · seed ${job.settings.seed}", color = Muted, style = MaterialTheme.typography.labelSmall)
                        Text(job.state, color = if (job.state == "failed") Color(0xFFFF889D) else Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (job.state == "queued") IconButton(onClick = { cancel(job.id) }) { Icon(Icons.Outlined.Cancel, "取消", tint = Muted) }
                }
                if (job.state == "running") LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), color = Violet, trackColor = Color(0x334A4F6B))
                job.error?.let { Text(it, color = Color(0xFFFF889D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun ModelsScreen(viewModel: QwenViewModel, status: ModelStatus?, repo: String, revision: String, busy: Boolean, progress: Int, message: String) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.importModels(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Memory, null, tint = Violet); Spacer(Modifier.width(8.dp)); Text("模型中心", color = Ink, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f)); Text("Q4_K_M", color = Mint, style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(8.dp)); Text("目标包：UC DiT + Qwen3-VL + Tiny VAE。原始 GGUF 需在电脑转换。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp)); Text(status?.message ?: "等待模型", color = if (status?.ready == true) Mint else Muted, style = MaterialTheme.typography.labelMedium)
            if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Violet, trackColor = Color(0x334A4F6B))
            Text(message, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(onClick = viewModel::checkRuntime, enabled = !busy && status?.ready == true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("检查推理引擎") }
        }
        GlassCard {
            Text("远程模型仓库", color = Ink, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp)); OutlinedTextField(repo, viewModel::setRepo, Modifier.fillMaxWidth(), label = { Text("Hugging Face owner/repo") }, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(6.dp)); OutlinedTextField(revision, viewModel::setRevision, Modifier.fillMaxWidth(), label = { Text("Revision") }, shape = RoundedCornerShape(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = viewModel::downloadModels, enabled = !busy && repo.isNotBlank(), modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("下载") }
                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导入") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: QwenViewModel, threads: Int, keepLoaded: Boolean, status: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Settings, null, tint = Violet); Spacer(Modifier.width(8.dp)); Text("偏好与性能", color = Ink, style = MaterialTheme.typography.titleLarge) }; Spacer(Modifier.height(12.dp)); Text("CPU 线程：$threads", color = Ink); Slider(value = threads.toFloat(), onValueChange = { viewModel.setCpuThreads(it.toInt()) }, valueRange = 2f..8f, steps = 5); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keepLoaded, viewModel::setKeepModelsLoaded); Text("保持模型常驻", color = Ink) }; Text("默认关闭，降低 24GB 手机的内存峰值。", color = Muted, style = MaterialTheme.typography.labelSmall); Text("当前：$status", color = Muted, style = MaterialTheme.typography.labelSmall) }
        GlassCard { Text("运行配置", color = Ink, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); Text("DiT · Adreno OpenCL", color = Muted); Text("Qwen3-VL 文本/视觉 · CPU int4", color = Muted); Text("Tiny VAE · FP16 GPU", color = Muted); Text("模型许可：Qwen Research License", color = Muted, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, accent: Color = Violet, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(24.dp)).border(1.dp, GlassStroke, RoundedCornerShape(24.dp)), color = Glass, shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun ModeChip(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) { FilterChip(selected, onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(17.dp)) }) }
@Composable
private fun PresetChip(label: String, icon: ImageVector, onClick: () -> Unit) { FilterChip(false, onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) }) }
@Composable
private fun MetricChip(name: String, value: String, modifier: Modifier = Modifier) { Surface(modifier, color = Color(0x331E2439), shape = RoundedCornerShape(15.dp)) { Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { Text(name, color = Muted, style = MaterialTheme.typography.labelSmall); Text(value, color = Ink, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = Violet, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(12.dp)); Text(title, color = Ink, style = MaterialTheme.typography.titleMedium); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) } }
private fun tabIcon(tab: WorkspaceTab): ImageVector = when (tab) { WorkspaceTab.Generate -> Icons.Outlined.AutoAwesome; WorkspaceTab.Queue -> Icons.Outlined.Queue; WorkspaceTab.Models -> Icons.Outlined.Memory; WorkspaceTab.Settings -> Icons.Outlined.Settings }
private fun tabLabel(tab: WorkspaceTab): String = when (tab) { WorkspaceTab.Generate -> "生成"; WorkspaceTab.Queue -> "队列"; WorkspaceTab.Models -> "模型"; WorkspaceTab.Settings -> "设置" }
