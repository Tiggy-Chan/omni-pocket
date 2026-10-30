package com.tiggy.qwenimage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.GenerationJob
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.ImageGenerationBackend
import com.tiggy.qwenimage.data.MossCheckpoint
import com.tiggy.qwenimage.data.MossInputMode
import com.tiggy.qwenimage.data.MossSettings
import com.tiggy.qwenimage.data.displayName
import com.tiggy.qwenimage.data.hasVae
import com.tiggy.qwenimage.data.isMoss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class ImageSheet { Backend, Layout, Params }

private data class SizePreset(val width: Int, val height: Int, val label: String)

private val MossSizePresets = listOf(
    SizePreset(512, 512, "512²"),
    SizePreset(1024, 1024, "1024²"),
    SizePreset(1024, 768, "横屏"),
    SizePreset(768, 1024, "竖屏")
)

private val QwenSizes = listOf(320, 384, 512)

private const val ImagePickerMime = "image/*"

/**
 * Image workspace: a transcript of image jobs (oldest first, the queue is the transcript itself)
 * and a composer for the active backend. Backend, layout and parameters live in sheets.
 */
@Composable
fun ImageWorkspace(
    state: QwenUiState,
    viewModel: QwenViewModel,
    onMenu: (() -> Unit)?,
    onPreview: (String) -> Unit,
    onModels: () -> Unit
) {
    val backend = state.imageBackend
    val settings = state.settings
    val moss = state.mossSettings
    val isQwen = backend == ImageGenerationBackend.QwenLocal
    val features = state.modelStatus?.manifest?.features.orEmpty()
    val editAvailable = "image-edit" in features
    val layoutMode = backend.isMoss && moss.inputMode == MossInputMode.Layout
    val prompt = if (isQwen) settings.prompt else moss.prompt
    val backendReady = backendReady(state, backend)
    val needsReference = isQwen && settings.mode == GenerationMode.ImageEdit && settings.inputUri == null
    val sendEnabled = prompt.isNotBlank() && backendReady && !needsReference
    val isLocalMoss = backend == ImageGenerationBackend.MossLocal
    val layoutSettings = if (isLocalMoss) moss.copy(width = moss.local.width, height = moss.local.height) else moss
    var sheet by remember { mutableStateOf<ImageSheet?>(null) }

    val firstPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setInput(uri)
    }
    val secondPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setInput(uri, second = true)
    }

    LaunchedEffect(Unit) {
        if (state.modelStatus == null) viewModel.refreshModelStatus()
        if (state.mossModelStatus == null) viewModel.refreshMossModelStatus()
    }
    val placeholder = when {
        layoutMode -> "描述整体画面…"
        isQwen && settings.mode == GenerationMode.ImageEdit -> "描述要怎样修改…"
        else -> "描述画面…"
    }
    val composerError = state.imageTask.error
        ?.takeIf { !state.imageTask.running && it != state.jobs.lastOrNull()?.error }
    val modelHint = !backendReady && state.jobs.isNotEmpty()
    val hasStatus = composerError != null || needsReference || modelHint || (isQwen && settings.inputUri != null)
    val statusContent: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            composerError?.let {
                Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
            if (needsReference) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑需要参考图", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { viewModel.setMode(GenerationMode.TextToImage) },
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) { Text("改为文生图") }
                }
            }
            if (modelHint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模型尚未就绪", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onModels, modifier = Modifier.heightIn(min = 40.dp)) { Text("打开模型") }
                }
            }
            if (isQwen && settings.inputUri != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComposerReference(settings.inputUri, 1) {
                        val second = settings.inputUri2
                        if (second != null) {
                            viewModel.setInput(null, second = true)
                            viewModel.setInput(second)
                        } else {
                            viewModel.setInput(null)
                            viewModel.setMode(GenerationMode.TextToImage)
                        }
                    }
                    settings.inputUri2?.let { uri ->
                        ComposerReference(uri, 2) { viewModel.setInput(null, second = true) }
                    }
                }
            }
        }
    }

    WorkspaceScaffold(
        title = "图像",
        subtitle = backend.displayName(),
        onMenu = onMenu,
        composer = { haze ->
            OmniComposer(
                hazeState = haze,
                value = prompt,
                onValueChange = { value ->
                    if (isQwen) viewModel.setPrompt(value)
                    else viewModel.updateMossSettings { it.copy(prompt = value) }
                },
                placeholder = placeholder,
                onSend = viewModel::enqueue,
                sendEnabled = sendEnabled,
                status = if (hasStatus) statusContent else null,
                tools = {
                    if (isQwen && editAvailable) {
                        val canAttach = settings.inputUri2 == null
                        IconButton(
                            onClick = {
                                if (settings.inputUri == null) firstPicker.launch(arrayOf(ImagePickerMime))
                                else secondPicker.launch(arrayOf(ImagePickerMime))
                            },
                            enabled = canAttach,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Outlined.AddPhotoAlternate,
                                if (settings.inputUri == null) "添加参考图" else "添加第二张参考图",
                                tint = if (canAttach) OmniColors.Muted else OmniColors.Dim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    ComposerChip(backend.displayName(), onClick = { sheet = ImageSheet.Backend })
                    if (backend.isMoss) {
                        ComposerChip("布局", onClick = { sheet = ImageSheet.Layout }, selected = layoutMode)
                    }
                    ComposerChip("", onClick = { sheet = ImageSheet.Params }, icon = Icons.Outlined.Tune, contentDescription = "参数")
                }
            )
        }
    ) { padding ->
        if (state.jobs.isEmpty()) {
            ImageEmptyState(padding, backendReady, onModels)
        } else {
            ImageTranscript(state, viewModel, padding, "image-edit" in features, onPreview)
        }
    }

    when (sheet) {
        ImageSheet.Backend -> OmniSheet("生成方式", onDismiss = { sheet = null }) {
            ImageBackendOptions(state) { target ->
                viewModel.setImageBackend(target)
                sheet = null
            }
        }
        ImageSheet.Layout -> OmniSheet("布局", onDismiss = { sheet = null }) {
            SheetScroll {
                OmniTabs(
                    items = MossInputMode.entries,
                    selected = moss.inputMode,
                    label = { if (it == MossInputMode.Prompt) "提示词" else "布局" },
                    onSelect = { mode -> viewModel.updateMossSettings { it.copy(inputMode = mode) } }
                )
                if (moss.inputMode == MossInputMode.Layout) {
                    MossLayoutEditor(layoutSettings, viewModel::updateMossSettings)
                }
            }
        }
        ImageSheet.Params -> OmniSheet("参数", onDismiss = { sheet = null }) {
            SheetScroll {
                if (isQwen) QwenParams(state, viewModel) else MossParams(state, viewModel)
            }
        }
        null -> Unit
    }
}

private fun backendReady(state: QwenUiState, backend: ImageGenerationBackend): Boolean = when (backend) {
    ImageGenerationBackend.QwenLocal -> state.modelStatus?.ready == true
    ImageGenerationBackend.MossLocal -> state.mossModelStatus?.ready == true
    ImageGenerationBackend.MossWorkstation -> true
}

@Composable
private fun ImageEmptyState(padding: PaddingValues, backendReady: Boolean, onModels: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(
            start = 32.dp, end = 32.dp,
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("描述你想生成的画面", color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
            if (!backendReady) {
                Text("当前模型尚未就绪", color = OmniColors.Dim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onModels, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开模型") }
            }
        }
    }
}

@Composable
private fun ImageTranscript(
    state: QwenUiState,
    viewModel: QwenViewModel,
    padding: PaddingValues,
    editAvailable: Boolean,
    onPreview: (String) -> Unit
) {
    val jobs = state.jobs
    val listState = rememberLazyListState()
    LaunchedEffect(jobs.size) {
        if (jobs.isNotEmpty()) listState.animateScrollToItem(0)
    }
    ReadingColumn(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(jobs.asReversed(), key = { it.id }) { job ->
                ImageJobEntry(
                    job = job,
                    state = state,
                    latest = job.id == jobs.lastOrNull()?.id,
                    editAvailable = editAvailable,
                    viewModel = viewModel,
                    onPreview = onPreview
                )
            }
        }
    }
}

@Composable
private fun ImageJobEntry(
    job: GenerationJob,
    state: QwenUiState,
    latest: Boolean,
    editAvailable: Boolean,
    viewModel: QwenViewModel,
    onPreview: (String) -> Unit
) {
    val active = state.activeTask?.id == job.id
    val references = if (job.backend == ImageGenerationBackend.QwenLocal && job.settings.mode == GenerationMode.ImageEdit) {
        listOfNotNull(job.settings.inputUri, job.settings.inputUri2)
    } else {
        emptyList()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RequestBubble(job.settings.prompt)
        if (references.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                references.forEach { ReferenceThumb(it, 36.dp) }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(jobCaption(job), color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
            when (job.state) {
                "done" -> ImageResult(job, editAvailable, viewModel, onPreview)
                "running" -> {
                    if (active) {
                        TaskLine(state.imageTask, onCancel = { viewModel.cancelJob(job.id) })
                    } else {
                        Text("生成中", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                "queued" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("排队中", color = OmniColors.Dim, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.cancelJob(job.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("取消", color = OmniColors.Muted)
                    }
                }
                else -> {
                    val label = when (job.state) {
                        "cancelled" -> "已取消"
                        "interrupted" -> "已中断"
                        else -> "失败"
                    }
                    Text(label, color = if (job.state == "cancelled") OmniColors.Muted else OmniColors.Danger,
                        style = MaterialTheme.typography.bodySmall)
                    job.error?.takeIf { it.isNotBlank() && job.state != "cancelled" }?.let {
                        Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                    ImageActions {
                        ImageAction(Icons.Outlined.Replay, "复用参数") { viewModel.reuseJob(job) }
                    }
                }
            }
            if (latest && (active || job.state == "failed")) OmniLogDisclosure(state.imageTask.logs)
        }
    }
}

@Composable
private fun ImageResult(
    job: GenerationJob,
    editAvailable: Boolean,
    viewModel: QwenViewModel,
    onPreview: (String) -> Unit
) {
    val context = LocalContext.current
    val path = job.outputPath
    val exists = remember(path) { path != null && File(path).isFile }
    if (path == null || !exists) {
        Text("图像文件已不存在", color = OmniColors.Dim, style = MaterialTheme.typography.bodySmall)
        ImageActions { ImageAction(Icons.Outlined.Replay, "复用参数") { viewModel.reuseJob(job) } }
        return
    }
    val width = job.outputWidth.takeIf { it > 0 } ?: job.settings.width
    val height = job.outputHeight.takeIf { it > 0 } ?: job.settings.height
    val aspect = (width.toFloat() / height.coerceAtLeast(1).toFloat()).coerceIn(0.25f, 4f)
    OmniAsyncImage(
        path = path,
        contentDescription = "生成的图像，点击放大",
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "放大") { onPreview(path) },
        maxDimension = 768
    )
    ImageActions {
        if (editAvailable) ImageAction(Icons.Outlined.Brush, "继续编辑") { viewModel.editImage(path) }
        ImageAction(Icons.Outlined.Videocam, "生成视频") { viewModel.useImageAsVideoFirstFrame(path) }
        ImageAction(Icons.Outlined.Replay, "复用参数") { viewModel.reuseJob(job) }
        ImageAction(Icons.Outlined.Share, "分享") {
            runCatching { shareMediaFile(context, File(path), "image/png", "分享图像") }
        }
    }
}

@Composable
private fun ImageActions(content: @Composable () -> Unit) {
    Row(Modifier.offset(x = (-14).dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun ImageAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
    }
}

private fun jobCaption(job: GenerationJob): String {
    val width = job.outputWidth.takeIf { it > 0 } ?: job.settings.width
    val height = job.outputHeight.takeIf { it > 0 } ?: job.settings.height
    return buildList {
        add(job.backend.displayName())
        add("${width}×${height}")
        add("${job.settings.steps} 步")
        if (job.state == "done" && job.elapsedMs > 0) add("${job.elapsedMs / 1000} 秒")
    }.joinToString(" · ")
}

@Composable
private fun ComposerReference(uri: Uri, index: Int, onRemove: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = "移除参考图 $index", onClick = onRemove)
    ) {
        ReferenceThumb(uri, 44.dp)
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp).clip(CircleShape)
                .background(OmniColors.Ink.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Close, null, tint = OmniColors.Background, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun ReferenceThumb(uri: Uri, size: Dp) {
    val bitmap = rememberReferenceBitmap(uri, 256)
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(OmniColors.Field)) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), "参考图", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Decodes a reference image; a missing or revoked URI yields null instead of throwing. */
@Composable
private fun rememberReferenceBitmap(uri: Uri, maxDimension: Int): Bitmap? {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, uri, maxDimension) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeReference(context, uri, maxDimension) }.getOrNull()
        }
    }.value
}

private fun decodeReference(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension && bounds.outHeight / (sample * 2) >= maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

@Composable
private fun ImageBackendOptions(state: QwenUiState, onSelect: (ImageGenerationBackend) -> Unit) {
    ImageGenerationBackend.entries.forEach { target ->
        val detail = when (target) {
            ImageGenerationBackend.QwenLocal -> if (state.modelStatus?.ready == true) "已就绪" else "模型未就绪"
            ImageGenerationBackend.MossLocal -> if (state.mossModelStatus?.ready == true) "已就绪" else "模型未就绪"
            ImageGenerationBackend.MossWorkstation -> "地址在设置中配置"
        }
        SettingRow(
            title = target.displayName(),
            detail = detail,
            onClick = { onSelect(target) }
        ) {
            if (state.imageBackend == target) {
                Icon(Icons.Outlined.Check, "当前", tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SheetScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun QwenParams(state: QwenUiState, viewModel: QwenViewModel) {
    val settings = state.settings
    val manifest = state.modelStatus?.manifest
    val features = manifest?.features.orEmpty()
    val tinyAvailable = manifest?.hasVae(true) == true
    val realAvailable = manifest?.hasVae(false) == true
    val fixedSteps = settings.officialTurbo || settings.turbo

    SectionLabel("尺寸")
    OmniTabs(
        items = QwenSizes,
        selected = if (settings.width == settings.height) settings.width else -1,
        label = { "${it}²" },
        onSelect = { viewModel.setSize(it, it) }
    )
    NumberField(
        label = "步数",
        value = if (settings.officialTurbo) "8" else if (settings.turbo) "6" else settings.steps.toString(),
        enabled = !fixedSteps
    ) { text -> text.toIntOrNull()?.let(viewModel::setSteps) }
    NumberField("Seed", settings.seed.toString()) { text -> text.toIntOrNull()?.let(viewModel::setSeed) }
    OmniTextField(
        value = settings.negativePrompt,
        onValueChange = viewModel::setNegativePrompt,
        label = "负向提示词",
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
    SwitchRow("官方 Turbo · 8 步", settings.officialTurbo, enabled = "official-turbo" in features,
        onChange = viewModel::setOfficialTurbo)
    SwitchRow("Tiny VAE", settings.useTinyVae, enabled = if (settings.useTinyVae) realAvailable else tinyAvailable,
        onChange = viewModel::setTinyVae)
    SwitchRow("透明背景", settings.transparent, onChange = viewModel::setTransparent)
}

@Composable
private fun MossParams(state: QwenUiState, viewModel: QwenViewModel) {
    val moss = state.mossSettings
    val local = state.imageBackend == ImageGenerationBackend.MossLocal
    val update: ((MossSettings) -> MossSettings) -> Unit = viewModel::updateMossSettings
    val outputWidth = if (local) moss.local.width else moss.width
    val outputHeight = if (local) moss.local.height else moss.height

    if (local) {
        val baseSelected = moss.checkpoint == MossCheckpoint.Base
        SettingRow(
            title = "检查点",
            detail = if (baseSelected) "当前为 Base，本机仅支持 SFT，点按切换" else "Base 仅工作站可用",
            onClick = if (baseSelected) ({ update { it.copy(checkpoint = MossCheckpoint.Sft) } }) else null
        ) {
            Text("SFT", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        SectionLabel("检查点")
        OmniTabs(
            items = MossCheckpoint.entries,
            selected = moss.checkpoint,
            label = { if (it == MossCheckpoint.Sft) "SFT" else "Base" },
            onSelect = { checkpoint -> update { it.copy(checkpoint = checkpoint) } }
        )
    }

    SectionLabel("尺寸 · $outputWidth×$outputHeight")
    OmniTabs(
        items = MossSizePresets,
        selected = MossSizePresets.firstOrNull { it.width == outputWidth && it.height == outputHeight }
            ?: SizePreset(outputWidth, outputHeight, ""),
        label = { it.label },
        onSelect = { preset ->
            update { s ->
                if (local) s.copy(local = s.local.copy(width = preset.width, height = preset.height))
                else s.copy(width = preset.width, height = preset.height)
            }
        }
    )

    NumberField("Seed", moss.seed.toString()) { text ->
        text.toLongOrNull()?.let { seed -> update { it.copy(seed = seed) } }
    }
    NumberField("步数", (if (local) moss.local.steps else moss.steps).toString()) { text ->
        text.toIntOrNull()?.let { steps ->
            update { if (local) it.copy(local = it.local.copy(steps = steps)) else it.copy(steps = steps) }
        }
    }
    NumberField("CFG", (if (local) moss.local.cfg else moss.cfg).toString()) { text ->
        text.toDoubleOrNull()?.let { cfg ->
            update { if (local) it.copy(local = it.local.copy(cfg = cfg)) else it.copy(cfg = cfg) }
        }
    }
    NumberField("Shift", moss.shift.toString()) { text ->
        text.toDoubleOrNull()?.let { shift -> update { it.copy(shift = shift) } }
    }
    OmniTextField(
        value = moss.negativePrompt,
        onValueChange = { value -> update { it.copy(negativePrompt = value) } },
        label = "负向提示词",
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )

    if (!local) {
        SectionLabel("采样器")
        OmniTabs(
            items = listOf("er_sde", "euler"),
            selected = moss.sampler,
            label = { it },
            onSelect = { sampler -> update { it.copy(sampler = sampler) } }
        )
        SectionLabel("调度器")
        OmniTabs(
            items = listOf("simple", "sgm_uniform"),
            selected = moss.scheduler,
            label = { it },
            onSelect = { scheduler -> update { it.copy(scheduler = scheduler) } }
        )
        SwitchRow("SLG", moss.slg.enabled) { on -> update { it.copy(slg = it.slg.copy(enabled = on)) } }
        if (moss.inputMode == MossInputMode.Prompt) {
            SwitchRow("提示词增强", moss.promptUpsampler) { on -> update { it.copy(promptUpsampler = on) } }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        title = title,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Numeric field that keeps what the user types until the parsed value actually differs. */
@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean = true,
    onValue: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value.toDoubleOrNull()) text = value
    }
    OmniTextField(
        value = text,
        onValueChange = {
            text = it
            onValue(it)
        },
        label = label,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}
