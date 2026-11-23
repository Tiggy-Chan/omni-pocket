package com.tiggy.qwenimage.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.StringRes
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.TaskUiState
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoJob
import com.tiggy.qwenimage.data.VideoSettings
import com.tiggy.qwenimage.data.formatBytesCompact
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

private sealed interface VideoBlock {
    val key: String
    val createdAt: Long

    data class Done(val video: VideoItem) : VideoBlock {
        override val key: String = "video-${video.id}"
        override val createdAt: Long = video.createdAt
    }

    data class Pending(val job: VideoJob) : VideoBlock {
        override val key: String = "job-${job.id}"
        override val createdAt: Long = job.createdAt
    }
}

@Composable
fun VideoWorkspace(
    state: QwenUiState,
    viewModel: QwenViewModel,
    onMenu: (() -> Unit)?,
    onModels: () -> Unit
) {
    val context = LocalContext.current
    val shareVideoTitle = tr(R.string.video_share_title)
    val settings = state.videoSettings
    val task = state.videoTask
    val activeCustomModel = state.customVideoModels.firstOrNull { it.id == settings.modelId }
    val isMiniMaxH3 = activeCustomModel?.metadata?.get("arch") == "minimax_h3"
    val modelLabel = activeCustomModel?.name ?: "Wan2.2 Turbo"
    // Stats model files, so check once per visit and after each job or model change rather than on every recomposition.
    val runtime = remember(task.running, state.wanModelStatus, settings.modelId, state.customVideoModels) {
        viewModel.videoRuntimeStatus()
    }
    val ready = runtime.localReady
    val firstFrame = settings.inputImageUri
    val parameterError = when {
        isMiniMaxH3 && !VideoSettings.isValidH3FrameCount(settings.numFrames, max = 56) -> tr(R.string.video_err_frames_h3)
        !isMiniMaxH3 && (settings.numFrames !in 5..41 || (settings.numFrames - 1) % 4 != 0) -> tr(R.string.video_err_frames)
        settings.steps !in 1..12 -> tr(R.string.video_err_steps)
        else -> null
    }
    val jobErrors = state.videoJobs.mapNotNull { it.error }
    val taskError = task.error?.takeIf { !task.running && it !in jobErrors }

    var showParams by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<VideoItem?>(null) }
    val listState = rememberLazyListState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setVideoInputImage(uri)
    }
    val blocks = remember(state.videoGallery, state.videoJobs) {
        buildList<VideoBlock> {
            addAll(state.videoGallery.map { VideoBlock.Done(it) })
            addAll(state.videoJobs.filter {
                it.resultPath == null && it.state != "completed" && it.state != "cancelled"
            }.map { VideoBlock.Pending(it) })
        }.sortedBy { it.createdAt }
    }
    val lead = if (ready) 0 else 1
    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty()) listState.scrollToItem(blocks.size - 1 + lead, 100_000)
    }

    WorkspaceScaffold(
        title = tr(R.string.nav_video),
        subtitle = if (ready) tr(R.string.common_on_device_tag, modelLabel) else tr(R.string.common_model_not_ready_tag, modelLabel),
        onMenu = onMenu,
        composer = { haze ->
            val status: (@Composable () -> Unit)? = if (firstFrame != null || parameterError != null || taskError != null) ({
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (firstFrame != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FirstFrameThumb(firstFrame, 40.dp, tr(R.string.video_first_frame_selected))
                            Spacer(Modifier.width(10.dp))
                            Text(tr(R.string.video_first_frame), color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.setVideoInputImage(null) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.Close, tr(R.string.video_remove_first_frame), tint = OmniColors.Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    parameterError?.let {
                        Text(it, color = OmniColors.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                    taskError?.let {
                        Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }) else null
            OmniComposer(
                hazeState = haze,
                value = settings.prompt,
                onValueChange = viewModel::setVideoPrompt,
                placeholder = tr(R.string.video_prompt_hint),
                onSend = viewModel::startVideoGeneration,
                sendEnabled = ready && settings.prompt.isNotBlank() && parameterError == null,
                running = task.running,
                onStop = viewModel::cancelVideoGeneration,
                status = status,
                tools = {
                    ComposerChip(
                        "", onClick = { imagePicker.launch(arrayOf("image/*")) },
                        icon = Icons.Outlined.AddPhotoAlternate,
                        selected = firstFrame != null,
                        contentDescription = if (firstFrame == null) tr(R.string.video_select_first_frame) else tr(R.string.video_change_first_frame)
                    )
                    ComposerChip(modelLabel.take(14), onClick = { showModelSheet = true })
                    ComposerChip("", onClick = { showParams = true }, icon = Icons.Outlined.Tune, contentDescription = tr(R.string.common_parameters))
                }
            )
        }
    ) { inner ->
        if (blocks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr(R.string.video_empty_title), color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium)
                    if (!ready) VideoModelNote(modelLabel, runtime.missingComponents, onModels)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = inner.calculateTopPadding() + 12.dp,
                    bottom = inner.calculateBottomPadding() + 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (!ready) {
                    item(key = "model-note") {
                        ReadingColumn { VideoModelNote(modelLabel, runtime.missingComponents, onModels) }
                    }
                }
                items(blocks, key = { it.key }) { block ->
                    ReadingColumn {
                        when (block) {
                            is VideoBlock.Done -> {
                                val video = block.video
                                val inputImage = state.videoJobs
                                    .firstOrNull { it.resultPath == video.videoPath }?.settings?.inputImageUri
                                VideoResultBlock(
                                    video = video,
                                    firstFrame = inputImage,
                                    loaded = playingId == video.id,
                                    onLoad = {
                                        viewModel.setActiveVideo(video)
                                        playingId = video.id
                                    },
                                    onReuse = { viewModel.reuseVideoItem(video) },
                                    onShare = { shareVideo(context, video, shareVideoTitle)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
                                    onDelete = { pendingDelete = video }
                                )
                            }
                            is VideoBlock.Pending -> {
                                val job = block.job
                                VideoJobBlock(
                                    job = job,
                                    active = task.running && state.activeTask?.id == job.id,
                                    latest = job.id == state.videoJobs.lastOrNull()?.id,
                                    task = task,
                                    onCancel = viewModel::cancelVideoGeneration
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showParams) {
        VideoParamsSheet(settings, isMiniMaxH3, viewModel) { showParams = false }
    }
    if (showModelSheet) {
        VideoModelSheet(
            state = state,
            onSelect = { modelId ->
                viewModel.selectVideoModel(modelId)
                showModelSheet = false
            },
            onManage = {
                showModelSheet = false
                onModels()
            },
            onDismiss = { showModelSheet = false }
        )
    }

    pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr(R.string.video_delete_title)) },
            text = { Text(tr(R.string.video_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (playingId == video.id) playingId = null
                    viewModel.deleteVideo(video)
                }) { Text(tr(R.string.common_delete), color = OmniColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(tr(R.string.common_cancel)) } },
            containerColor = OmniColors.Raised,
            titleContentColor = OmniColors.Ink,
            textContentColor = OmniColors.Muted
        )
    }
}

@Composable
private fun VideoModelNote(modelLabel: String, missing: List<String>, onModels: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.common_model_not_ready_tag, modelLabel), color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onModels, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(R.string.common_go_download)) }
        }
        Text(
            tr(R.string.video_missing_hint, missing.joinToString(", ")),
            color = OmniColors.Dim,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VideoModelSheet(
    state: QwenUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentLabel = tr(R.string.common_current)
    OmniSheet(tr(R.string.video_models_title), onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SettingRow(
                title = tr(R.string.common_builtin_tag, "Wan2.2 Turbo"),
                detail = if (state.wanModelStatus?.ready == true) tr(R.string.video_builtin_ready) else tr(R.string.common_not_downloaded),
                onClick = { onSelect("") }
            ) {
                if (state.videoSettings.modelId.isBlank()) {
                    Icon(Icons.Outlined.Check, currentLabel, tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
                }
            }
            if (state.customVideoModels.isNotEmpty()) {
                SectionLabel(tr(R.string.common_community_downloaded))
                state.customVideoModels.forEach { model ->
                    SettingRow(
                        title = model.name,
                        detail = "${model.repo} · ${formatBytesCompact(model.totalBytes)}",
                        onClick = { onSelect(model.id) }
                    ) {
                        if (state.videoSettings.modelId == model.id) {
                            Icon(Icons.Outlined.Check, currentLabel, tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            SettingRow(
                title = tr(R.string.video_search_more),
                detail = tr(R.string.video_search_more_sub),
                onClick = onManage
            )
        }
    }
}

@Composable
private fun VideoRequest(prompt: String, firstFrame: Uri?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (firstFrame != null) FirstFrameThumb(firstFrame, 72.dp, tr(R.string.video_first_frame))
        RequestBubble(prompt)
    }
}

@Composable
private fun FirstFrameThumb(uri: Uri, side: Dp, description: String) {
    val bitmap = rememberUriBitmap(uri, maxDimension = 256)
    val modifier = Modifier.size(side).clip(RoundedCornerShape(10.dp)).background(OmniColors.Field)
    if (bitmap == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AddPhotoAlternate, description, tint = OmniColors.Dim, modifier = Modifier.size(side / 2))
        }
    } else {
        Image(bitmap.asImageBitmap(), description, modifier, contentScale = ContentScale.Crop)
    }
}

@Composable
private fun VideoResultBlock(
    video: VideoItem,
    firstFrame: Uri?,
    loaded: Boolean,
    onLoad: () -> Unit,
    onReuse: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val metadata = videoMetadata(video)
    val playLabel = tr(R.string.common_play)
    val pauseLabel = tr(R.string.common_pause)
    val cannotPlayText = tr(R.string.video_cannot_play)
    var playing by remember(video.id, loaded) { mutableStateOf(false) }
    val playerRef = remember(video.id) { arrayOfNulls<VideoView>(1) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoRequest(videoDisplayName(video), firstFrame)
        Column {
            val frame = Modifier.mediaFrame(videoAspect(video)).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            if (loaded) {
                VideoPlayer(video.videoPath, frame, playerRef, playing, playLabel, pauseLabel, cannotPlayText) { playing = it }
            } else {
                Box(
                    frame.clickable(role = Role.Button, onClickLabel = playLabel, onClick = onLoad),
                    contentAlignment = Alignment.Center
                ) {
                    val thumbnail = video.thumbnailPath
                    if (thumbnail.isNullOrBlank()) {
                        Icon(Icons.Outlined.Movie, tr(R.string.nav_video), tint = OmniColors.Dim, modifier = Modifier.size(32.dp))
                    } else {
                        OmniAsyncImage(thumbnail, videoDisplayName(video), Modifier.fillMaxSize(),
                            maxDimension = 768, contentScale = ContentScale.Crop)
                    }
                    PlayBadge()
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(metadata, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 2.dp))
                if (loaded) {
                    IconButton(onClick = { togglePlayback(playerRef[0], playing) { playing = it } }, modifier = Modifier.size(48.dp)) {
                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (playing) pauseLabel else playLabel, tint = OmniColors.Muted, modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = onReuse, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Replay, tr(R.string.video_reuse_prompt), tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Share, tr(R.string.video_share_title), tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, tr(R.string.common_delete), tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayBadge() {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

private fun Modifier.mediaFrame(aspect: Float): Modifier =
    if (aspect < 1f) {
        height(340.dp).aspectRatio(aspect, matchHeightConstraintsFirst = true)
    } else {
        widthIn(max = 520.dp).fillMaxWidth().aspectRatio(aspect)
    }

private fun togglePlayback(player: VideoView?, playing: Boolean, onPlaying: (Boolean) -> Unit) {
    player ?: return
    if (playing) {
        player.pause()
        onPlaying(false)
    } else {
        if (player.duration > 0 && player.currentPosition >= player.duration - 150) {
            player.seekTo(0)
        }
        player.start()
        onPlaying(true)
    }
}

@Composable
private fun VideoPlayer(
    videoPath: String,
    modifier: Modifier,
    playerRef: Array<VideoView?>,
    playing: Boolean,
    playLabel: String,
    pauseLabel: String,
    cannotPlayText: String,
    onPlaying: (Boolean) -> Unit
) {
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).also { view ->
                    playerRef[0] = view
                    view.setOnPreparedListener { player ->
                        player.isLooping = false
                        playbackError = null
                        view.start()
                        onPlaying(true)
                    }
                    view.setOnCompletionListener { onPlaying(false) }
                    view.setOnErrorListener { _, _, _ ->
                        onPlaying(false)
                        playbackError = cannotPlayText
                        true
                    }
                }
            },
            update = { view ->
                if (loadedPath != videoPath) {
                    view.stopPlayback()
                    onPlaying(false)
                    playbackError = null
                    loadedPath = videoPath
                    view.setVideoURI(Uri.fromFile(File(videoPath)))
                }
            },
            onRelease = { view ->
                view.stopPlayback()
                view.setOnPreparedListener(null)
                view.setOnCompletionListener(null)
                view.setOnErrorListener(null)
                if (playerRef[0] === view) playerRef[0] = null
            },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().clickable(
                role = Role.Button,
                onClickLabel = if (playing) pauseLabel else playLabel
            ) { togglePlayback(playerRef[0], playing, onPlaying) },
            contentAlignment = Alignment.Center
        ) {
            if (!playing && playbackError == null) PlayBadge()
        }
        playbackError?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun VideoJobBlock(
    job: VideoJob,
    active: Boolean,
    latest: Boolean,
    task: TaskUiState,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoRequest(job.settings.prompt, job.settings.inputImageUri)
        if (active && job.state != "failed") {
            TaskLine(task, onCancel = onCancel)
        } else {
            TaskLine(TaskUiState(status = job.stage, error = job.error))
        }
        if (latest) OmniLogDisclosure(task.logs)
    }
}

private data class VideoResOption(val width: Int, val height: Int, @StringRes val labelRes: Int)

private val VideoResOptions = listOf(
    VideoResOption(480, 832, R.string.res_portrait),
    VideoResOption(832, 480, R.string.res_landscape),
    VideoResOption(480, 480, R.string.res_square),
    VideoResOption(384, 640, R.string.res_preview)
)

private val MiniMaxH3ResOptions = listOf(
    VideoResOption(480, 288, R.string.res_h3_landscape),
    VideoResOption(288, 480, R.string.res_h3_portrait),
    VideoResOption(512, 320, R.string.res_h3_wide),
    VideoResOption(640, 384, R.string.res_h3_hd)
)

@Composable
private fun VideoParamsSheet(
    settings: VideoSettings,
    isMiniMaxH3: Boolean,
    viewModel: QwenViewModel,
    onDismiss: () -> Unit
) {
    val localized = LocalLocalizedContext.current
    var seedText by remember(settings.seed) { mutableStateOf(settings.seed.toString()) }
    var cfgText by remember(settings.cfgScale) { mutableStateOf(String.format(Locale.US, "%.1f", settings.cfgScale)) }
    val resOptions = if (isMiniMaxH3) MiniMaxH3ResOptions else VideoResOptions
    val frameOptions = if (isMiniMaxH3) listOf(5, 22, 39, 56) else listOf(9, 17, 25, 33)
    OmniSheet(tr(R.string.common_parameters), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.video_resolution), color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(resOptions) { opt ->
                        ComposerChip(
                            label = localized.getString(opt.labelRes),
                            onClick = { viewModel.setVideoResolution(opt.width, opt.height) },
                            selected = settings.width == opt.width && settings.height == opt.height
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tr(if (isMiniMaxH3) R.string.video_frames_label_h3 else R.string.video_frames_label),
                    color = OmniColors.Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                OmniTabs(
                    items = frameOptions,
                    selected = settings.numFrames,
                    label = { localized.getString(R.string.common_frames_value, it) },
                    onSelect = viewModel::setVideoNumFrames
                )
            }
            if (isMiniMaxH3) {
                SettingRow(
                    title = tr(R.string.video_fps),
                    detail = "24 FPS"
                )
            } else {
                VideoIntSlider(tr(R.string.video_fps), "${settings.fps} FPS", settings.fps, 8..30, viewModel::setVideoFps)
            }
            VideoIntSlider(tr(R.string.common_steps), tr(R.string.common_steps_value, settings.steps), settings.steps, 1..12, viewModel::setVideoSteps)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OmniTextField(
                    value = cfgText,
                    onValueChange = { value ->
                        cfgText = value
                        value.toFloatOrNull()?.takeIf { it.isFinite() }?.let(viewModel::setVideoCfgScale)
                    },
                    label = tr(R.string.video_cfg_label),
                    modifier = Modifier.weight(1f)
                )
                OmniTextField(
                    value = seedText,
                    onValueChange = { value ->
                        seedText = value
                        value.toLongOrNull()?.let(viewModel::setVideoSeed)
                    },
                    label = tr(R.string.common_seed),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { viewModel.setVideoSeed(Random.nextLong(100_000, 100_000_000)) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, tr(R.string.common_seed), tint = OmniColors.Muted)
                }
            }
            OmniTextField(
                value = settings.negativePrompt,
                onValueChange = viewModel::setVideoNegativePrompt,
                label = tr(R.string.image_negative_prompt),
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            SettingRow(
                title = tr(R.string.settings_video_opencl),
                detail = tr(R.string.settings_video_opencl_detail),
                onClick = { viewModel.setVideoUseOpenCl(!settings.useOpenCl) },
                trailing = { Switch(checked = settings.useOpenCl, onCheckedChange = null) }
            )
            SettingRow(
                title = tr(R.string.image_vae_tiling),
                onClick = { viewModel.setVideoVaeTiling(!settings.vaeTiling) },
                trailing = { Switch(checked = settings.vaeTiling, onCheckedChange = null) }
            )
            if (isMiniMaxH3) {
                SettingRow(
                    title = tr(R.string.settings_video_disk_stream),
                    detail = tr(R.string.settings_video_disk_stream_detail),
                    onClick = { viewModel.setVideoDiskStreaming(!settings.diskStreaming) },
                    trailing = { Switch(checked = settings.diskStreaming, onCheckedChange = null) }
                )
            }
        }
    }
}

@Composable
private fun VideoIntSlider(label: String, valueLabel: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, color = OmniColors.Ink, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = OmniColors.Accent,
                activeTrackColor = OmniColors.Accent,
                inactiveTrackColor = OmniColors.Selected
            ),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label $valueLabel" }
        )
    }
}

private fun videoAspect(video: VideoItem): Float {
    val match = Regex("""(\d+)\s*[×xX*]\s*(\d+)""").find(video.resolution)
    val width = match?.groupValues?.get(1)?.toFloatOrNull()
    val height = match?.groupValues?.get(2)?.toFloatOrNull()
    return if (width != null && height != null && width > 0f && height > 0f) width / height else 480f / 832f
}

private fun videoDisplayName(video: VideoItem): String =
    video.prompt.ifBlank { File(video.videoPath).name }

private fun videoMetadata(video: VideoItem): String = buildList {
    video.resolution
        .takeIf { it.isNotBlank() && !it.equals("未知", ignoreCase = true) }
        ?.let(::add)
    video.durationSec
        .takeIf { it.isFinite() && it > 0f }
        ?.let { add(formatMediaDuration(it)) }
    video.fps.takeIf { it > 0 }?.let { add("$it FPS") }
    video.fileSizeBytes.takeIf { it > 0L }?.let { add(formatFileSize(it)) }
}.joinToString(" · ")

private fun formatFileSize(bytes: Long): String {
    val size = bytes.toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        size >= gb -> String.format(Locale.getDefault(), "%.1f GB", size / gb)
        size >= mb -> String.format(Locale.getDefault(), "%.1f MB", size / mb)
        size >= kb -> String.format(Locale.getDefault(), "%.1f KB", size / kb)
        else -> "$bytes B"
    }
}

private fun shareVideo(context: Context, video: VideoItem, shareTitle: String): String? {
    val file = File(video.videoPath)
    if (!file.isFile) return null
    val mimeType = when (file.extension.lowercase()) {
        "webm" -> "video/webm"
        "mp4" -> "video/mp4"
        else -> return null
    }
    return runCatching {
        shareMediaFile(context, file, mimeType, shareTitle)
    }.fold(
        onSuccess = { null },
        onFailure = { error -> error.message }
    )
}
