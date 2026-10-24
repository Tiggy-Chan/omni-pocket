package com.tiggy.qwenimage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.GenerationBackend
import com.tiggy.qwenimage.data.ImageGenerationBackend
import com.tiggy.qwenimage.data.MossCheckpoint
import com.tiggy.qwenimage.data.MossInputMode
import com.tiggy.qwenimage.data.MossLayoutItem
import com.tiggy.qwenimage.data.MossLayoutLabel
import com.tiggy.qwenimage.data.MossSettings
import com.tiggy.qwenimage.data.TaskDomain
import java.io.File
import kotlin.math.max
import kotlin.math.min

private val MossAccent = Color(0xFF9AD6C5)
private val MossSurface = Color(0xF0171822)
private val MossText = Color(0xFFF5F4F8)
private val MossMuted = Color(0xFFAAA6B2)

@Composable
fun ImageBackendSelector(selected: ImageGenerationBackend, onSelect: (ImageGenerationBackend) -> Unit) {
    OmniTabs(
        items = ImageGenerationBackend.entries,
        selected = selected,
        accent = OmniColors.Violet,
        label = { if (it == ImageGenerationBackend.QwenLocal) "Qwen · 本机" else "MOSS · 工作站" },
        onSelect = onSelect
    )
}

@Composable
fun MossStudioScreen(state: QwenUiState, viewModel: QwenViewModel) {
    val settings = state.mossSettings
    val update: ((MossSettings) -> MossSettings) -> Unit = viewModel::updateMossSettings
    var showAdvanced by remember { mutableStateOf(false) }
    val task = state.imageTask

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            MossSection {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MossChoice("SFT", settings.checkpoint == MossCheckpoint.Sft, Modifier.weight(1f)) {
                        update { it.copy(checkpoint = MossCheckpoint.Sft) }
                    }
                    MossChoice("Base", settings.checkpoint == MossCheckpoint.Base, Modifier.weight(1f)) {
                        update { it.copy(checkpoint = MossCheckpoint.Base) }
                    }
                    MossChoice("提示词", settings.inputMode == MossInputMode.Prompt, Modifier.weight(1f)) {
                        update { it.copy(inputMode = MossInputMode.Prompt) }
                    }
                    MossChoice("布局", settings.inputMode == MossInputMode.Layout, Modifier.weight(1f)) {
                        update { it.copy(inputMode = MossInputMode.Layout) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = settings.prompt,
                    onValueChange = { value -> update { it.copy(prompt = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    label = { Text(if (settings.inputMode == MossInputMode.Prompt) "画面描述" else "布局总体描述") },
                    placeholder = { Text(if (settings.inputMode == MossInputMode.Prompt) "描述最终画面和需要呈现的文字" else "描述整体场景、风格和文字内容") }
                )
                if (settings.inputMode == MossInputMode.Layout) {
                    Spacer(Modifier.height(12.dp))
                    MossLayoutEditor(settings = settings, onSettings = update)
                }
            }
        }

        item {
            MossSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("输出", color = MossText, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text("${settings.width} × ${settings.height}", color = MossMuted, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple(512, 512, "512²"),
                        Triple(1024, 1024, "1024²"),
                        Triple(1024, 768, "横屏"),
                        Triple(768, 1024, "竖屏")
                    ).forEach { (width, height, label) ->
                        MossChoice(
                            label,
                            settings.width == width && settings.height == height,
                            Modifier.weight(1f)
                        ) { update { it.copy(width = width, height = height) } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.serverUrl,
                    onValueChange = { value -> update { it.copy(serverUrl = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("ComfyUI 工作站") },
                    placeholder = { Text("http://127.0.0.1:8188") }
                )
                TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAdvanced) "收起参数" else "更多参数")
                    Icon(if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (showAdvanced) {
                    MossNumberField("Seed", settings.seed.toString()) { value ->
                        value.toLongOrNull()?.let { seed -> update { it.copy(seed = seed) } }
                    }
                    MossNumberField("步数", settings.steps.toString()) { value ->
                        value.toIntOrNull()?.let { steps -> update { it.copy(steps = steps) } }
                    }
                    MossNumberField("CFG", settings.cfg.toString()) { value ->
                        value.toDoubleOrNull()?.let { cfg -> update { it.copy(cfg = cfg) } }
                    }
                    MossNumberField("Shift", settings.shift.toString()) { value ->
                        value.toDoubleOrNull()?.let { shift -> update { it.copy(shift = shift) } }
                    }
                    OutlinedTextField(
                        value = settings.negativePrompt,
                        onValueChange = { value -> update { it.copy(negativePrompt = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("负向提示词") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MossChoice("er_sde", settings.sampler == "er_sde", Modifier.weight(1f)) {
                            update { it.copy(sampler = "er_sde") }
                        }
                        MossChoice("euler", settings.sampler == "euler", Modifier.weight(1f)) {
                            update { it.copy(sampler = "euler") }
                        }
                        MossChoice("SLG 22", settings.slg.enabled, Modifier.weight(1f)) {
                            update { it.copy(slg = it.slg.copy(enabled = !it.slg.enabled)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MossChoice("simple", settings.scheduler == "simple", Modifier.weight(1f)) {
                            update { it.copy(scheduler = "simple") }
                        }
                        MossChoice("sgm_uniform", settings.scheduler == "sgm_uniform", Modifier.weight(1f)) {
                            update { it.copy(scheduler = "sgm_uniform") }
                        }
                    }
                    if (settings.inputMode == MossInputMode.Prompt) {
                        MossChoice("提示词增强", settings.promptUpsampler, Modifier.fillMaxWidth()) {
                            update { it.copy(promptUpsampler = !it.promptUpsampler) }
                        }
                    }
                }
            }
        }

        item {
            OmniPrimaryButton(
                text = if (task.running) "加入图像队列" else "生成图像",
                onClick = viewModel::enqueue,
                enabled = settings.prompt.isNotBlank(),
                busy = task.running
            )
            Spacer(Modifier.height(8.dp))
            val active = state.activeTask
            OmniTaskStatus(
                task = task,
                onCancel = if (task.running && active?.domain == TaskDomain.Image && active.backend == GenerationBackend.Workstation) {
                    { viewModel.cancelJob(active.id) }
                } else null
            )
            if (task.logs.isNotEmpty()) OmniLogDisclosure(task.logs)
        }

        task.resultPath?.let { path ->
            item {
                MossSection {
                    Text("最新图像", color = MossText, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OmniAsyncImage(
                        path = path,
                        contentDescription = "生成结果",
                        modifier = Modifier.fillMaxWidth(),
                        maxDimension = 1440
                    )
                    Text(File(path).name, color = MossMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MossSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MossSurface,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun MossChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = modifier)
}

@Composable
private fun MossNumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        singleLine = true,
        label = { Text(label) }
    )
}

@Composable
private fun MossLayoutEditor(settings: MossSettings, onSettings: ((MossSettings) -> MossSettings) -> Unit) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val items = settings.layout.items
    val currentSettings by rememberUpdatedState(settings)
    val currentOnSettings by rememberUpdatedState(onSettings)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var resizing by remember { mutableStateOf(false) }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val next = items + defaultLayoutItem(MossLayoutLabel.Text, items.size)
                selected = items.size
                currentOnSettings { it.copy(layout = it.layout.copy(items = next)) }
            }) {
                Icon(Icons.Outlined.Add, null)
                Text("文字")
            }
            OutlinedButton(onClick = {
                val next = items + defaultLayoutItem(MossLayoutLabel.Object, items.size)
                selected = items.size
                currentOnSettings { it.copy(layout = it.layout.copy(items = next)) }
            }) {
                Icon(Icons.Outlined.Add, null)
                Text("对象")
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(settings.width.toFloat() / settings.height.toFloat())
                .background(Color(0xFF0C0E14), RoundedCornerShape(12.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInput(settings.width, settings.height) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val currentItems = currentSettings.layout.items
                            val index = currentItems.indexOfLast { item ->
                                item.rect(canvasSize).contains(offset)
                            }.takeIf { it >= 0 }
                            selected = index
                            resizing = index != null && offset.distanceTo(currentItems[index].rect(canvasSize).bottomRight) < 56f
                        },
                        onDrag = { change, amount ->
                            val index = selected ?: return@detectDragGestures
                            val currentItems = currentSettings.layout.items
                            val item = currentItems.getOrNull(index) ?: return@detectDragGestures
                            val dx = (amount.x / canvasSize.width.coerceAtLeast(1)).toDouble()
                            val dy = (amount.y / canvasSize.height.coerceAtLeast(1)).toDouble()
                            val updated = if (resizing) item.resized(dx, dy) else item.moved(dx, dy)
                            currentOnSettings { state ->
                                state.copy(layout = state.layout.copy(items = state.layout.items.mapIndexed { i, value -> if (i == index) updated else value }))
                            }
                            change.consume()
                        }
                    )
                }
                .semantics { contentDescription = "布局画布。拖动移动元素，拖动右下角调整大小。" }
        ) {
            currentSettings.layout.items.forEachIndexed { index, item ->
                val rect = item.rect(size)
                val color = if (item.label == MossLayoutLabel.Text) Color(0xFFE5C07B) else MossAccent
                drawRect(color.copy(alpha = 0.12f), rect.topLeft, rect.size)
                drawRect(if (selected == index) Color.White else color, rect.topLeft, rect.size, style = Stroke(width = if (selected == index) 3f else 2f))
                if (selected == index) drawCircle(Color.White, radius = 8f, center = rect.bottomRight)
            }
        }
        Text("${items.size} 个元素", color = MossMuted, style = MaterialTheme.typography.labelSmall)
        items.forEachIndexed { index, item ->
            TextButton(onClick = { selected = index }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${index + 1}. ${if (item.label == MossLayoutLabel.Text) "文字" else "对象"} · ${item.caption}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected == index) MossText else MossMuted
                )
            }
        }
        selected?.let { index ->
            items.getOrNull(index)?.let { item ->
                OutlinedTextField(
                    value = item.caption,
                    onValueChange = { value ->
                        currentOnSettings { state ->
                            state.copy(layout = state.layout.copy(items = state.layout.items.mapIndexed { i, old -> if (i == index) old.copy(caption = value) else old }))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("元素描述") }
                )
                if (item.label == MossLayoutLabel.Text) {
                    OutlinedTextField(
                        value = item.text,
                        onValueChange = { value ->
                            currentOnSettings { state ->
                                state.copy(layout = state.layout.copy(items = state.layout.items.mapIndexed { i, old -> if (i == index) old.copy(text = value) else old }))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("要渲染的文字") }
                    )
                }
                TextButton(onClick = {
                    currentOnSettings { state ->
                        state.copy(layout = state.layout.copy(items = state.layout.items.filterIndexed { i, _ -> i != index }))
                    }
                    selected = null
                }) {
                    Icon(Icons.Outlined.Delete, "删除布局元素")
                    Text("删除元素")
                }
            }
        }
    }
}

private fun defaultLayoutItem(label: MossLayoutLabel, index: Int): MossLayoutItem {
    val offset = (index % 5) * 0.04
    return MossLayoutItem(
        label = label,
        caption = if (label == MossLayoutLabel.Text) "标题文字" else "画面主体",
        text = if (label == MossLayoutLabel.Text) "TEXT" else "",
        left = 0.15 + offset,
        top = 0.15 + offset,
        right = 0.65 + offset,
        bottom = 0.45 + offset
    )
}

private fun MossLayoutItem.rect(size: Size): Rect = Rect(
    (left * size.width).toFloat(),
    (top * size.height).toFloat(),
    (right * size.width).toFloat(),
    (bottom * size.height).toFloat()
)

private fun MossLayoutItem.rect(size: IntSize): Rect = rect(Size(size.width.toFloat(), size.height.toFloat()))

private fun MossLayoutItem.moved(dx: Double, dy: Double): MossLayoutItem {
    val width = right - left
    val height = bottom - top
    val newLeft = (left + dx).coerceIn(0.0, 1.0 - width)
    val newTop = (top + dy).coerceIn(0.0, 1.0 - height)
    return copy(left = newLeft, top = newTop, right = newLeft + width, bottom = newTop + height)
}

private fun MossLayoutItem.resized(dx: Double, dy: Double): MossLayoutItem = copy(
    right = (right + dx).coerceIn(min(1.0, left + 0.02), 1.0),
    bottom = (bottom + dy).coerceIn(min(1.0, top + 0.02), 1.0)
)

private fun Offset.distanceTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return max(dx * dx + dy * dy, 0f).let { kotlin.math.sqrt(it) }
}
