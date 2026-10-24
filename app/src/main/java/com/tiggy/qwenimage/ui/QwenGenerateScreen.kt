package com.tiggy.qwenimage.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.GenerationBackend
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.TaskDomain
import com.tiggy.qwenimage.data.hasVae
import java.io.File

@Composable
fun QwenGenerateScreen(state: QwenUiState, viewModel: QwenViewModel, onPreviewImage: (String) -> Unit) {
    val settings = state.settings
    val manifest = state.modelStatus?.manifest
    val features = manifest?.features.orEmpty()
    val editAvailable = "image-edit" in features
    val officialTurboAvailable = "official-turbo" in features
    val tinyAvailable = manifest?.hasVae(true) == true
    val realAvailable = manifest?.hasVae(false) == true
    val task = state.imageTask
    val modelReady = state.modelStatus?.ready == true
    var showAdvanced by remember { mutableStateOf(false) }
    val firstPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setInput(uri)
    }
    val secondPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setInput(uri, second = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (editAvailable) {
            item {
                OmniTabs(
                    items = GenerationMode.entries,
                    selected = settings.mode,
                    accent = OmniColors.Violet,
                    label = { if (it == GenerationMode.TextToImage) "文生图" else "图片编辑" },
                    onSelect = viewModel::setMode
                )
            }
        }

        item {
            ContentCard {
                OmniTextField(
                    value = settings.prompt,
                    onValueChange = viewModel::setPrompt,
                    label = "提示词",
                    placeholder = "描述你想看到的画面",
                    minLines = 4,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (settings.mode == GenerationMode.ImageEdit) {
            item {
                ContentCard(title = "参考图") {
                    ReferenceImageRow("图片 1", settings.inputUri, true, viewModel) {
                        firstPicker.launch(arrayOf("image/*"))
                    }
                    if (settings.inputUri != null) {
                        ReferenceImageRow("图片 2（可选）", settings.inputUri2, false, viewModel) {
                            secondPicker.launch(arrayOf("image/*"))
                        }
                    }
                }
            }
        }

        item {
            OmniPrimaryButton(
                text = when {
                    task.running -> "加入图像队列"
                    settings.mode == GenerationMode.ImageEdit -> "开始编辑"
                    else -> "生成图像"
                },
                onClick = viewModel::enqueue,
                enabled = modelReady && settings.prompt.isNotBlank(),
                busy = task.running
            )
            Spacer(Modifier.height(8.dp))
            val active = state.activeTask
            OmniTaskStatus(
                task = task,
                onCancel = if (task.running && active?.domain == TaskDomain.Image && active.backend == GenerationBackend.LocalQwen) {
                    { viewModel.cancelJob(active.id) }
                } else null
            )
            if (!modelReady) Text("请先在模型页完成 Qwen 校验", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAdvanced) "收起参数" else "高级参数")
                Icon(if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
        }

        if (showAdvanced) {
            item {
                ContentCard(title = if (settings.mode == GenerationMode.ImageEdit) "像素预算与采样" else "尺寸与采样") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(320 to 320, 384 to 384, 512 to 512).forEach { (width, height) ->
                            OutlinedButton(
                                onClick = { viewModel.setSize(width, height) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                enabled = settings.width != width || settings.height != height
                            ) { Text("${width}²") }
                        }
                    }
                    OmniTextField(
                        value = settings.negativePrompt,
                        onValueChange = viewModel::setNegativePrompt,
                        label = "负向提示词",
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val fixedSteps = settings.officialTurbo || settings.turbo
                    OmniTextField(
                        value = if (settings.officialTurbo) "8" else if (settings.turbo) "6" else settings.steps.toString(),
                        onValueChange = { value -> value.toIntOrNull()?.let(viewModel::setSteps) },
                        label = "采样步数",
                        enabled = !fixedSteps,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OmniTextField(
                        value = settings.seed.toString(),
                        onValueChange = { value -> value.toIntOrNull()?.let(viewModel::setSeed) },
                        label = "Seed",
                        modifier = Modifier.fillMaxWidth()
                    )
                    SettingToggle("官方 Turbo · 8 步", settings.officialTurbo, officialTurboAvailable, viewModel::setOfficialTurbo)
                    SettingToggle(
                        "Tiny VAE",
                        settings.useTinyVae,
                        if (settings.useTinyVae) realAvailable else tinyAvailable,
                        viewModel::setTinyVae
                    )
                    SettingToggle("透明背景（提示词）", settings.transparent, true, viewModel::setTransparent)
                }
            }
        }

        task.resultPath?.let { path ->
            item {
                ContentCard(title = "最新图像") {
                    val dimensions = state.jobs.lastOrNull { it.outputPath == path }?.let {
                        if (it.outputWidth > 0 && it.outputHeight > 0) "${it.outputWidth} × ${it.outputHeight}" else null
                    }
                    if (dimensions != null) Text(dimensions, color = OmniColors.Muted, style = MaterialTheme.typography.labelMedium)
                    OmniAsyncImage(
                        path = path,
                        contentDescription = "生成结果，点击放大",
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPreviewImage(path) },
                        maxDimension = 1440
                    )
                    Text(File(path).name, color = OmniColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        val completed = state.jobs.filter { it.state == "done" && it.outputPath != null && File(it.outputPath).exists() && it.outputPath != task.resultPath }.asReversed()
        if (completed.isNotEmpty()) {
            item {
                Text("历史作品", color = OmniColors.Ink, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(completed, key = { it.id }) { job ->
                        val path = requireNotNull(job.outputPath)
                        ContentCard(modifier = Modifier.width(150.dp)) {
                            OmniAsyncImage(
                                path = path,
                                contentDescription = job.settings.prompt,
                                modifier = Modifier.size(118.dp).clip(RoundedCornerShape(12.dp)).clickable { onPreviewImage(path) },
                                maxDimension = 384
                            )
                            Text(job.settings.prompt, color = OmniColors.Ink, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (job.backend == com.tiggy.qwenimage.data.ImageGenerationBackend.MossWorkstation) "MOSS" else "Qwen",
                                color = OmniColors.Muted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        if (task.logs.isNotEmpty()) item { OmniLogDisclosure(task.logs) }
        if (state.jobs.isEmpty() && task.resultPath == null) {
            item { OmniEmptyState(Icons.Outlined.Collections, "还没有作品", "生成的图像会保存在本机") }
        }
    }
}

@Composable
private fun ReferenceImageRow(label: String, uri: Uri?, required: Boolean, viewModel: QwenViewModel, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (uri != null) {
            val bitmap = rememberUriBitmap(uri, 384)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = OmniColors.Ink, style = MaterialTheme.typography.bodyMedium)
            Text(if (uri == null) if (required) "必需" else "未选择" else "已选择", color = OmniColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.height(48.dp)) {
            Icon(Icons.Outlined.AddPhotoAlternate, if (uri == null) "选择$label" else "更换$label", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (uri == null) "选择" else "更换")
        }
        if (uri != null) {
            IconButton(onClick = { viewModel.setInput(null, second = label.startsWith("图片 2")) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, "移除$label")
            }
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, color = if (enabled) OmniColors.Ink else OmniColors.Dim, style = MaterialTheme.typography.bodyMedium)
    }
}
