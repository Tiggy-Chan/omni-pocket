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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.TaskUiState
import com.tiggy.qwenimage.data.VideoBackend
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoJob
import com.tiggy.qwenimage.data.VideoSettings
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
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val settings = state.videoSettings
    val task = state.videoTask
    val backend = viewModel.videoBackend()
    val local = backend == VideoBackend.LocalWan
    val workstation = !local
    val firstFrame = settings.inputImageUri
    val firstFrameBlocked = workstation && firstFrame != null
    val parameterError = when {
        local && settings.numFrames !in 5..25 -> "本机最多 25 帧，请调整帧数。"
        local && settings.steps !in 1..6 -> "本机最多 6 步，请调整步数。"
        firstFrameBlocked -> "工作站不支持首帧，请移除后再生成。"
        else -> null
    }
    val jobErrors = state.videoJobs.mapNotNull { it.error }
    val taskError = task.error?.takeIf { !task.running && it !in jobErrors }

    var showParams by remember { mutableStateOf(false) }
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
    val lead = if (workstation) 1 else 0
    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty()) listState.scrollToItem(blocks.size - 1 + lead, 100_000)
    }

    WorkspaceScaffold(
        title = "视频",
        subtitle = if (local) "Wan2.2 · 本机" else "工作站",
        onMenu = onMenu,
        composer = { haze ->
            val status: (@Composable () -> Unit)? = if (firstFrame != null || parameterError != null || taskError != null) ({
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (firstFrame != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FirstFrameThumb(firstFrame, 40.dp, "已选首帧")
                            Spacer(Modifier.width(10.dp))
                            Text("首帧", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.setVideoInputImage(null) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.Close, "移除首帧", tint = OmniColors.Muted, modifier = Modifier.size(18.dp))
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
                placeholder = "描述主体、动作和镜头",
                onSend = viewModel::startVideoGeneration,
                sendEnabled = settings.prompt.isNotBlank() && parameterError == null,
                running = task.running,
                onStop = viewModel::cancelVideoGeneration,
                status = status,
                tools = {
                    ComposerChip(
                        "", onClick = { imagePicker.launch(arrayOf("image/*")) },
                        icon = Icons.Outlined.AddPhotoAlternate,
                        selected = firstFrame != null,
                        contentDescription = if (firstFrame == null) "选择首帧" else "更换首帧"
                    )
                    ComposerChip("参数", onClick = { showParams = true }, icon = Icons.Outlined.Tune)
                }
            )
        }
    ) { inner ->
        if (blocks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("描述一段画面", color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium)
                    if (workstation) VideoWorkstationNote(onSettings)
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
                if (workstation) {
                    item(key = "workstation-note") {
                        ReadingColumn { VideoWorkstationNote(onSettings) }
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
                                    onShare = { shareVideo(context, video)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
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
        VideoParamsSheet(settings, local, viewModel) { showParams = false }
    }

    pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除视频？") },
            text = { Text("将删除视频及关联文件，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (playingId == video.id) playingId = null
                    viewModel.deleteVideo(video)
                }) { Text("删除", color = OmniColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            containerColor = OmniColors.Raised,
            titleContentColor = OmniColors.Ink,
            textContentColor = OmniColors.Muted
        )
    }
}

@Composable
private fun VideoWorkstationNote(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("使用工作站 · 地址在设置中", color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("设置") }
    }
}

@Composable
private fun VideoRequest(prompt: String, firstFrame: Uri?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (firstFrame != null) FirstFrameThumb(firstFrame, 72.dp, "首帧")
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
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val metadata = videoMetadata(video)
    var playing by remember(video.id, loaded) { mutableStateOf(false) }
    val playerRef = remember(video.id) { arrayOfNulls<VideoView>(1) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoRequest(videoDisplayName(video), firstFrame)
        Column {
            val frame = Modifier.mediaFrame(videoAspect(video)).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            if (loaded) {
                VideoPlayer(video.videoPath, frame, playerRef, playing) { playing = it }
            } else {
                Box(
                    frame.clickable(role = Role.Button, onClickLabel = "播放视频", onClick = onLoad),
                    contentAlignment = Alignment.Center
                ) {
                    val thumbnail = video.thumbnailPath
                    if (thumbnail.isNullOrBlank()) {
                        Icon(Icons.Outlined.Movie, "视频缩略图不可用", tint = OmniColors.Dim, modifier = Modifier.size(32.dp))
                    } else {
                        OmniAsyncImage(thumbnail, "${videoDisplayName(video)} 的缩略图", Modifier.fillMaxSize(),
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
                            if (playing) "暂停" else "播放", tint = OmniColors.Muted, modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Share, "分享视频", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, "删除视频", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
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
                        playbackError = "无法播放此视频"
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
                onClickLabel = if (playing) "暂停" else "播放"
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

@Composable
private fun VideoParamsSheet(settings: VideoSettings, local: Boolean, viewModel: QwenViewModel, onDismiss: () -> Unit) {
    var seedText by remember(settings.seed) { mutableStateOf(settings.seed.toString()) }
    OmniSheet("参数", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (local) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("帧数", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    OmniTabs(
                        items = listOf(9, 17, 25),
                        selected = settings.numFrames,
                        label = { "$it 帧" },
                        onSelect = viewModel::setVideoNumFrames
                    )
                }
            } else {
                VideoIntSlider("帧数", "${settings.numFrames} 帧", settings.numFrames, 5..81, viewModel::setVideoNumFrames)
            }
            VideoIntSlider("帧率", "${settings.fps} FPS", settings.fps, 8..30, viewModel::setVideoFps)
            if (local) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("步数", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                    OmniTabs(
                        items = listOf(3, 4, 5, 6),
                        selected = settings.steps,
                        label = { "$it 步" },
                        onSelect = viewModel::setVideoSteps
                    )
                }
            } else {
                VideoIntSlider("步数", "${settings.steps} 步", settings.steps, 1..10, viewModel::setVideoSteps)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniTextField(
                    value = seedText,
                    onValueChange = { value ->
                        seedText = value
                        value.toLongOrNull()?.let(viewModel::setVideoSeed)
                    },
                    label = "随机种子",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { viewModel.setVideoSeed(Random.nextLong(100_000, 100_000_000)) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, "随机种子", tint = OmniColors.Muted)
                }
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

private fun shareVideo(context: Context, video: VideoItem): String? {
    val file = File(video.videoPath)
    if (!file.isFile) return "视频文件不存在，无法分享"
    val mimeType = when (file.extension.lowercase()) {
        "webm" -> "video/webm"
        "mp4" -> "video/mp4"
        else -> return "暂不支持分享此格式"
    }
    return runCatching {
        shareMediaFile(context, file, mimeType, "分享视频")
    }.fold(
        onSuccess = { null },
        onFailure = { error -> "分享失败：${error.message ?: "无法打开分享面板"}" }
    )
}
