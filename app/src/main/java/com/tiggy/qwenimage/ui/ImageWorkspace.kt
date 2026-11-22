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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.annotation.StringRes
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.GenerationJob
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.ImageGenerationBackend
import com.tiggy.qwenimage.data.MossInputMode
import com.tiggy.qwenimage.data.MossSettings
import com.tiggy.qwenimage.data.displayName
import com.tiggy.qwenimage.data.formatBytesCompact
import com.tiggy.qwenimage.data.hasVae
import com.tiggy.qwenimage.data.isMoss
import com.tiggy.qwenimage.engine.CustomSdSettings
import com.tiggy.qwenimage.engine.SUPPORTED_SD_SAMPLERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class ImageSheet { Backend, Layout, Params }

private data class SizePreset(
    val width: Int,
    val height: Int,
    val fixedLabel: String? = null,
    @StringRes val labelRes: Int = 0
)

private val MossSizePresets = listOf(
    SizePreset(512, 512, fixedLabel = "512²"),
    SizePreset(1024, 1024, fixedLabel = "1024²"),
    SizePreset(1024, 768, labelRes = R.string.image_landscape),
    SizePreset(768, 1024, labelRes = R.string.image_portrait)
)

private val CustomSdSizePresets = listOf(
    SizePreset(512, 512, fixedLabel = "512²"),
    SizePreset(768, 768, fixedLabel = "768²"),
    SizePreset(768, 512, labelRes = R.string.image_landscape),
    SizePreset(512, 768, labelRes = R.string.image_portrait)
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
    val customSd = state.customSdSettings
    val selectedCustomModel = state.customImageModels.firstOrNull { it.id == customSd.modelId }
    val isQwen = backend == ImageGenerationBackend.QwenLocal
    val isCustomSd = backend == ImageGenerationBackend.CustomSd
    val features = state.modelStatus?.manifest?.features.orEmpty()
    val editAvailable = "image-edit" in features
    val layoutMode = backend.isMoss && moss.inputMode == MossInputMode.Layout
    val prompt = when (backend) {
        ImageGenerationBackend.QwenLocal -> settings.prompt
        ImageGenerationBackend.MossLocal -> moss.prompt
        ImageGenerationBackend.CustomSd -> customSd.prompt
    }
    val backendLabel = if (isCustomSd) (selectedCustomModel?.name ?: backend.displayName()) else backend.displayName()
    val backendReady = backendReady(state, backend)
    val needsReference = isQwen && settings.mode == GenerationMode.ImageEdit && settings.inputUri == null
    val sendEnabled = prompt.isNotBlank() && backendReady && !needsReference
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
        layoutMode -> tr(R.string.image_prompt_overall_hint)
        isQwen && settings.mode == GenerationMode.ImageEdit -> tr(R.string.image_edit_hint)
        else -> tr(R.string.image_prompt_hint)
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
                    Text(tr(R.string.image_edit_needs_ref), color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { viewModel.setMode(GenerationMode.TextToImage) },
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) { Text(tr(R.string.image_switch_t2i)) }
                }
            }
            if (modelHint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.image_not_ready_short), color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onModels, modifier = Modifier.heightIn(min = 40.dp)) { Text(tr(R.string.common_open_models)) }
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
        title = tr(R.string.nav_image),
        subtitle = backendLabel,
        onMenu = onMenu,
        composer = { haze ->
            OmniComposer(
                hazeState = haze,
                value = prompt,
                onValueChange = { value ->
                    when (backend) {
                        ImageGenerationBackend.QwenLocal -> viewModel.setPrompt(value)
                        ImageGenerationBackend.MossLocal -> viewModel.updateMossSettings { it.copy(prompt = value) }
                        ImageGenerationBackend.CustomSd -> viewModel.updateCustomSdSettings { it.copy(prompt = value) }
                    }
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
                                if (settings.inputUri == null) tr(R.string.image_add_ref) else tr(R.string.image_add_ref2),
                                tint = if (canAttach) OmniColors.Muted else OmniColors.Dim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    ComposerChip(backendLabel.take(18), onClick = { sheet = ImageSheet.Backend })
                    if (backend.isMoss) {
                        ComposerChip(tr(R.string.image_layout), onClick = { sheet = ImageSheet.Layout }, selected = layoutMode)
                    }
                    ComposerChip("", onClick = { sheet = ImageSheet.Params }, icon = Icons.Outlined.Tune, contentDescription = tr(R.string.common_parameters))
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
        ImageSheet.Backend -> OmniSheet(tr(R.string.image_engine_sheet_title), onDismiss = { sheet = null }) {
            ImageBackendOptions(
                state = state,
                onSelectBuiltIn = { target ->
                    viewModel.setImageBackend(target)
                    sheet = null
                },
                onSelectCustom = { modelId ->
                    viewModel.selectCustomImageModel(modelId)
                    sheet = null
                },
                onManageModels = {
                    sheet = null
                    onModels()
                }
            )
        }
        ImageSheet.Layout -> {
            val promptTabLabel = tr(R.string.image_prompt_tab)
            val layoutTabLabel = tr(R.string.image_layout)
            OmniSheet(layoutTabLabel, onDismiss = { sheet = null }) {
                SheetScroll {
                    OmniTabs(
                        items = MossInputMode.entries,
                        selected = moss.inputMode,
                        label = { if (it == MossInputMode.Prompt) promptTabLabel else layoutTabLabel },
                        onSelect = { mode -> viewModel.updateMossSettings { it.copy(inputMode = mode) } }
                    )
                    if (moss.inputMode == MossInputMode.Layout) {
                        MossLayoutEditor(moss, viewModel::updateMossSettings)
                    }
                }
            }
        }
        ImageSheet.Params -> OmniSheet(tr(R.string.common_parameters), onDismiss = { sheet = null }) {
            SheetScroll {
                when (backend) {
                    ImageGenerationBackend.QwenLocal -> QwenParams(state, viewModel)
                    ImageGenerationBackend.MossLocal -> MossParams(state, viewModel)
                    ImageGenerationBackend.CustomSd -> CustomSdParams(state, viewModel)
                }
            }
        }
        null -> Unit
    }
}

private fun backendReady(state: QwenUiState, backend: ImageGenerationBackend): Boolean = when (backend) {
    ImageGenerationBackend.QwenLocal -> state.modelStatus?.ready == true
    ImageGenerationBackend.MossLocal -> state.mossModelStatus?.ready == true
    ImageGenerationBackend.CustomSd -> state.customImageModels.any { it.id == state.customSdSettings.modelId }
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
            Text(tr(R.string.image_empty_title), color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
            if (!backendReady) {
                Text(tr(R.string.image_model_not_ready), color = OmniColors.Dim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onModels, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(R.string.common_open_models)) }
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
                        Text(tr(R.string.image_status_generating), color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                "queued" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.image_status_queued), color = OmniColors.Dim, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.cancelJob(job.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(tr(R.string.common_cancel), color = OmniColors.Muted)
                    }
                }
                else -> {
                    val label = when (job.state) {
                        "cancelled" -> tr(R.string.image_status_cancelled)
                        "interrupted" -> tr(R.string.chat_interrupted)
                        else -> tr(R.string.image_status_failed)
                    }
                    Text(label, color = if (job.state == "cancelled") OmniColors.Muted else OmniColors.Danger,
                        style = MaterialTheme.typography.bodySmall)
                    job.error?.takeIf { it.isNotBlank() && job.state != "cancelled" }?.let {
                        Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                    ImageActions {
                        ImageAction(Icons.Outlined.Replay, tr(R.string.image_reuse_params)) { viewModel.reuseJob(job) }
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
    val shareTitle = tr(R.string.image_share_title)
    val zoomLabel = tr(R.string.image_zoom)
    val path = job.outputPath
    val exists = remember(path) { path != null && File(path).isFile }
    if (path == null || !exists) {
        Text(tr(R.string.image_file_missing), color = OmniColors.Dim, style = MaterialTheme.typography.bodySmall)
        ImageActions { ImageAction(Icons.Outlined.Replay, tr(R.string.image_reuse_params)) { viewModel.reuseJob(job) } }
        return
    }
    val width = job.outputWidth.takeIf { it > 0 } ?: job.settings.width
    val height = job.outputHeight.takeIf { it > 0 } ?: job.settings.height
    val aspect = (width.toFloat() / height.coerceAtLeast(1).toFloat()).coerceIn(0.25f, 4f)
    OmniAsyncImage(
        path = path,
        contentDescription = zoomLabel,
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = zoomLabel) { onPreview(path) },
        maxDimension = 768
    )
    ImageActions {
        if (editAvailable) ImageAction(Icons.Outlined.Brush, tr(R.string.common_continue_edit)) { viewModel.editImage(path) }
        ImageAction(Icons.Outlined.Videocam, tr(R.string.common_make_video)) { viewModel.useImageAsVideoFirstFrame(path) }
        ImageAction(Icons.Outlined.Replay, tr(R.string.image_reuse_params)) { viewModel.reuseJob(job) }
        ImageAction(Icons.Outlined.Share, tr(R.string.common_share)) {
            runCatching { shareMediaFile(context, File(path), "image/png", shareTitle) }
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

@Composable
private fun jobCaption(job: GenerationJob): String {
    val width = job.outputWidth.takeIf { it > 0 } ?: job.settings.width
    val height = job.outputHeight.takeIf { it > 0 } ?: job.settings.height
    val stepsText = tr(R.string.common_steps_value, job.settings.steps)
    val elapsedText = if (job.state == "done" && job.elapsedMs > 0) tr(R.string.common_seconds_value, (job.elapsedMs / 1000).toInt()) else null
    return buildList {
        add(if (job.backend == ImageGenerationBackend.CustomSd) job.customModelName ?: job.backend.displayName() else job.backend.displayName())
        add("${width}×${height}")
        add(stepsText)
        elapsedText?.let(::add)
    }.joinToString(" · ")
}

@Composable
private fun ComposerReference(uri: Uri, index: Int, onRemove: () -> Unit) {
    val removeLabel = tr(R.string.image_remove_ref, index)
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = removeLabel, onClick = onRemove)
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
    val refCd = tr(R.string.image_ref_label)
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(OmniColors.Field)) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), refCd, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
private fun ImageBackendOptions(
    state: QwenUiState,
    onSelectBuiltIn: (ImageGenerationBackend) -> Unit,
    onSelectCustom: (String) -> Unit,
    onManageModels: () -> Unit
) {
    val readyText = tr(R.string.common_ready)
    val notReadyText = tr(R.string.image_not_ready_short)
    val currentText = tr(R.string.common_current)
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        listOf(ImageGenerationBackend.QwenLocal, ImageGenerationBackend.MossLocal).forEach { target ->
            val detail = when (target) {
                ImageGenerationBackend.QwenLocal -> if (state.modelStatus?.ready == true) readyText else notReadyText
                ImageGenerationBackend.MossLocal -> if (state.mossModelStatus?.ready == true) readyText else notReadyText
                ImageGenerationBackend.CustomSd -> ""
            }
            SettingRow(
                title = target.displayName(),
                detail = detail,
                onClick = { onSelectBuiltIn(target) }
            ) {
                if (state.imageBackend == target) {
                    Icon(Icons.Outlined.Check, currentText, tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (state.customImageModels.isNotEmpty()) {
            SectionLabel(tr(R.string.common_community_downloaded))
            state.customImageModels.forEach { model ->
                val selected = state.imageBackend == ImageGenerationBackend.CustomSd && state.customSdSettings.modelId == model.id
                SettingRow(
                    title = model.name,
                    detail = "${model.repo} · ${formatBytesCompact(model.totalBytes)}",
                    onClick = { onSelectCustom(model.id) }
                ) {
                    if (selected) {
                        Icon(Icons.Outlined.Check, currentText, tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        SettingRow(
            title = tr(R.string.image_search_more),
            detail = tr(R.string.image_search_more_sub),
            onClick = onManageModels
        )
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

    SectionLabel(tr(R.string.image_size))
    OmniTabs(
        items = QwenSizes,
        selected = if (settings.width == settings.height) settings.width else -1,
        label = { "${it}²" },
        onSelect = { viewModel.setSize(it, it) }
    )
    NumberField(
        label = tr(R.string.common_steps),
        value = if (settings.officialTurbo) "8" else if (settings.turbo) "6" else settings.steps.toString(),
        enabled = !fixedSteps
    ) { text -> text.toIntOrNull()?.let(viewModel::setSteps) }
    NumberField(tr(R.string.common_seed), settings.seed.toString()) { text -> text.toIntOrNull()?.let(viewModel::setSeed) }
    OmniTextField(
        value = settings.negativePrompt,
        onValueChange = viewModel::setNegativePrompt,
        label = tr(R.string.image_negative_prompt),
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
    SwitchRow(tr(R.string.image_official_turbo), settings.officialTurbo, enabled = "official-turbo" in features,
        onChange = viewModel::setOfficialTurbo)
    SwitchRow("Tiny VAE", settings.useTinyVae, enabled = if (settings.useTinyVae) realAvailable else tinyAvailable,
        onChange = viewModel::setTinyVae)
    SwitchRow(tr(R.string.image_transparent), settings.transparent, onChange = viewModel::setTransparent)
}

@Composable
private fun MossParams(state: QwenUiState, viewModel: QwenViewModel) {
    val localized = LocalLocalizedContext.current
    val moss = state.mossSettings
    val local = moss.local
    val update: ((MossSettings) -> MossSettings) -> Unit = viewModel::updateMossSettings

    SectionLabel(tr(R.string.image_size_val, local.width, local.height))
    OmniTabs(
        items = MossSizePresets,
        selected = MossSizePresets.firstOrNull { it.width == local.width && it.height == local.height }
            ?: SizePreset(local.width, local.height, fixedLabel = ""),
        label = { it.fixedLabel ?: localized.getString(it.labelRes) },
        onSelect = { preset -> update { s -> s.copy(local = s.local.copy(width = preset.width, height = preset.height)) } }
    )

    NumberField(tr(R.string.common_seed), moss.seed.toString()) { text ->
        text.toLongOrNull()?.let { seed -> update { it.copy(seed = seed) } }
    }
    NumberField(tr(R.string.common_steps), local.steps.toString()) { text ->
        text.toIntOrNull()?.let { steps -> update { it.copy(local = it.local.copy(steps = steps)) } }
    }
    NumberField("CFG", local.cfg.toString()) { text ->
        text.toDoubleOrNull()?.let { cfg -> update { it.copy(local = it.local.copy(cfg = cfg)) } }
    }
    NumberField("Shift", moss.shift.toString()) { text ->
        text.toDoubleOrNull()?.let { shift -> update { it.copy(shift = shift) } }
    }
    OmniTextField(
        value = moss.negativePrompt,
        onValueChange = { value -> update { it.copy(negativePrompt = value) } },
        label = tr(R.string.image_negative_prompt),
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
    SwitchRow(tr(R.string.image_opencl_gpu), local.useOpenCl) { enabled ->
        update { it.copy(local = it.local.copy(useOpenCl = enabled)) }
    }
    SwitchRow(tr(R.string.image_vae_tiling), local.vaeTiling) { enabled ->
        update { it.copy(local = it.local.copy(vaeTiling = enabled)) }
    }
}

@Composable
private fun CustomSdParams(state: QwenUiState, viewModel: QwenViewModel) {
    val localized = LocalLocalizedContext.current
    val sd = state.customSdSettings
    val update: ((CustomSdSettings) -> CustomSdSettings) -> Unit = viewModel::updateCustomSdSettings

    SectionLabel(tr(R.string.image_size_val, sd.width, sd.height))
    OmniTabs(
        items = CustomSdSizePresets,
        selected = CustomSdSizePresets.firstOrNull { it.width == sd.width && it.height == sd.height }
            ?: SizePreset(sd.width, sd.height, fixedLabel = ""),
        label = { it.fixedLabel ?: localized.getString(it.labelRes) },
        onSelect = { preset -> update { it.copy(width = preset.width, height = preset.height) } }
    )
    SectionLabel(tr(R.string.image_sampler))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SUPPORTED_SD_SAMPLERS) { sampler ->
            ComposerChip(
                label = samplerDisplayLabel(sampler),
                onClick = { update { it.copy(sampler = sampler) } },
                selected = sd.sampler == sampler
            )
        }
    }
    NumberField(tr(R.string.common_seed), sd.seed.toString()) { text ->
        text.toLongOrNull()?.takeIf { it >= 0L }?.let { seed -> update { it.copy(seed = seed) } }
    }
    NumberField(tr(R.string.common_steps), sd.steps.toString()) { text ->
        text.toIntOrNull()?.let { steps -> update { it.copy(steps = steps.coerceIn(1, 60)) } }
    }
    NumberField("CFG", sd.cfg.toString()) { text ->
        text.toDoubleOrNull()?.let { cfg -> update { it.copy(cfg = cfg) } }
    }
    OmniTextField(
        value = sd.negativePrompt,
        onValueChange = { value -> update { it.copy(negativePrompt = value) } },
        label = tr(R.string.image_negative_prompt),
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
    SwitchRow(tr(R.string.image_opencl_gpu), sd.useOpenCl) { enabled -> update { it.copy(useOpenCl = enabled) } }
    SwitchRow(tr(R.string.image_vae_tiling), sd.vaeTiling) { enabled -> update { it.copy(vaeTiling = enabled) } }
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
