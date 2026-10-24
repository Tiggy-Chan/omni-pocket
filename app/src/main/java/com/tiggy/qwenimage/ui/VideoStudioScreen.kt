package com.tiggy.qwenimage.ui

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.VideoBackend
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoSettings
import com.tiggy.qwenimage.data.VideoStudioTab
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun VideoStudioScreen(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showNotice: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            OmniTabs(
                items = VideoStudioTab.entries,
                selected = state.videoTab,
                accent = OmniColors.Violet,
                label = { tab ->
                    when (tab) {
                        VideoStudioTab.Compose -> "创作"
                        VideoStudioTab.Gallery -> "媒体库"
                    }
                },
                onSelect = viewModel::selectVideoTab
            )
            Spacer(Modifier.height(12.dp))
            when (state.videoTab) {
                VideoStudioTab.Compose -> VideoComposeView(state, viewModel)
                VideoStudioTab.Gallery -> VideoGalleryView(state, viewModel, showNotice)
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        )
    }
}

@Composable
private fun VideoComposeView(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    val settings = state.videoSettings
    val task = state.videoTask
    val backend = viewModel.videoBackend()
    val workstation = backend == VideoBackend.Workstation
    val firstFrameBlocked = workstation && settings.inputImageUri != null
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setVideoInputImage(uri)
    }
    val localParameterError = when {
        backend == VideoBackend.LocalWan && settings.numFrames !in 5..25 -> "本机最多 25 帧，请调整帧数。"
        backend == VideoBackend.LocalWan && settings.steps !in 1..6 -> "本机最多 6 步，请调整步数。"
        else -> null
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "当前后端",
                color = OmniColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            VideoBackendBadge(backend)
        }

        ContentCard {
            OmniTextField(
                value = settings.prompt,
                onValueChange = viewModel::setVideoPrompt,
                label = "提示词",
                placeholder = "描述主体、动作和镜头运动",
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ContentCard(title = "首帧（可选）") {
            if (settings.inputImageUri != null) {
                FirstFramePreview(settings.inputImageUri)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = if (settings.inputImageUri == null) "选择首帧" else "更换首帧",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (settings.inputImageUri == null) "选择首帧" else "更换首帧")
                }
                if (settings.inputImageUri != null) {
                    OutlinedButton(
                        onClick = { viewModel.setVideoInputImage(null) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "移除首帧",
                            tint = OmniColors.Rose,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("移除", color = OmniColors.Rose)
                    }
                }
            }
            if (firstFrameBlocked) {
                Text(
                    "工作站不支持首帧，请移除后再生成。",
                    color = OmniColors.Amber,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OmniPrimaryButton(
            text = if (task.running) "正在生成" else "生成视频",
            onClick = viewModel::startVideoGeneration,
            enabled = !task.running && settings.prompt.isNotBlank() &&
                !firstFrameBlocked && localParameterError == null,
            busy = task.running,
            accent = OmniColors.VioletDeep
        )
        localParameterError?.let { message ->
            Text(message, color = OmniColors.Amber, style = MaterialTheme.typography.bodySmall)
        }

        ContentCard(title = "任务状态") {
            OmniTaskStatus(task = task, onCancel = viewModel::cancelVideoGeneration)
            OmniLogDisclosure(logs = task.logs)
        }

        ContentCard(title = "生成参数") {
            VideoParameters(
                settings = settings,
                workstation = workstation,
                onFramesChange = viewModel::setVideoNumFrames,
                onFpsChange = viewModel::setVideoFps,
                onStepsChange = viewModel::setVideoSteps
            )
            if (workstation) {
                OmniTextField(
                    value = settings.serverUrl,
                    onValueChange = viewModel::setVideoServerUrl,
                    label = "工作站地址",
                    placeholder = "http://电脑IP:8998",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun VideoBackendBadge(backend: VideoBackend) {
    val label = when (backend) {
        VideoBackend.LocalWan -> "本机 Wan"
        VideoBackend.Workstation -> "工作站"
        else -> "未知"
    }
    val accent = if (backend == VideoBackend.Workstation) OmniColors.Amber else OmniColors.Mint
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Text(
            label,
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FirstFramePreview(uri: Uri) {
    val bitmap = rememberUriBitmap(uri, maxDimension = 768)
    val modifier = Modifier
        .fillMaxWidth()
        .height(180.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(Color.Black)
    if (bitmap == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            OmniEmptyState(icon = Icons.Outlined.Image, title = "首帧预览")
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "已选视频首帧",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun VideoParameters(
    settings: VideoSettings,
    workstation: Boolean,
    onFramesChange: (Int) -> Unit,
    onFpsChange: (Int) -> Unit,
    onStepsChange: (Int) -> Unit
) {
    if (workstation) {
        VideoIntSlider(
            label = "帧数",
            value = settings.numFrames,
            range = 5..81,
            valueSuffix = "帧",
            onValueChange = onFramesChange
        )
    } else {
        Text("帧数", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
        OmniTabs(
            items = listOf(9, 17, 25),
            selected = settings.numFrames,
            accent = OmniColors.Violet,
            label = { "$it 帧" },
            onSelect = onFramesChange
        )
    }

    VideoIntSlider(
        label = "帧率",
        value = settings.fps,
        range = 8..30,
        valueSuffix = "FPS",
        onValueChange = onFpsChange
    )

    if (workstation) {
        VideoIntSlider(
            label = "步数",
            value = settings.steps,
            range = 1..10,
            valueSuffix = "步",
            onValueChange = onStepsChange
        )
    } else {
        Text("步数", color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
        OmniTabs(
            items = listOf(3, 4, 5, 6),
            selected = settings.steps,
            accent = OmniColors.Violet,
            label = { "$it 步" },
            onSelect = onStepsChange
        )
    }
}

@Composable
private fun VideoIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    valueSuffix: String,
    onValueChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = OmniColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$value $valueSuffix",
                color = OmniColors.Ink,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = SliderDefaults.colors(
                thumbColor = OmniColors.Violet,
                activeTrackColor = OmniColors.Violet,
                inactiveTrackColor = OmniColors.ContentStrong
            )
        )
    }
}

@Composable
private fun VideoGalleryView(
    state: QwenUiState,
    viewModel: QwenViewModel,
    showNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val activeVideo = state.activeVideo
        ?.takeIf { active -> state.videoGallery.any { it.id == active.id } }
        ?: state.videoGallery.firstOrNull()
    var pendingDelete by remember { mutableStateOf<VideoItem?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        activeVideo?.let { video ->
            item(key = "active-video-player") {
                VideoPlayerCard(
                    video = video,
                    onShare = { shareVideo(context, video)?.let(showNotice) },
                    onDelete = { pendingDelete = video }
                )
            }
        }
        if (state.videoGallery.isEmpty()) {
            item(key = "gallery-empty") {
                ContentCard {
                    OmniEmptyState(
                        icon = Icons.Outlined.VideoLibrary,
                        title = "还没有视频",
                        subtitle = "生成后可在这里播放和分享"
                    )
                }
            }
        } else {
            item(key = "gallery-heading") {
                Text(
                    "全部视频 (${state.videoGallery.size})",
                    color = OmniColors.Ink,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(
                items = state.videoGallery,
                key = { "video-${it.id}" }
            ) { video ->
                VideoGalleryItem(
                    video = video,
                    selected = video.id == activeVideo?.id,
                    onClick = { viewModel.setActiveVideo(video) }
                )
            }
        }
    }

    pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除视频？") },
            text = { Text("将删除视频及关联文件，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteVideo(video)
                        if (viewModel.state.value.videoGallery.none { it.id == video.id }) {
                            showNotice("视频已删除")
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("删除", color = OmniColors.Rose)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("取消")
                }
            },
            containerColor = OmniColors.ContentStrong,
            titleContentColor = OmniColors.Ink,
            textContentColor = OmniColors.Muted
        )
    }
}

@Composable
private fun VideoPlayerCard(
    video: VideoItem,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    ContentCard(title = "当前视频") {
        VideoPlayer(video.videoPath)
        Text(
            videoDisplayName(video),
            color = OmniColors.Ink,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        VideoMetadata(video)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Share, contentDescription = "分享视频", tint = OmniColors.Violet)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除视频", tint = OmniColors.Rose)
            }
        }
    }
}

@Composable
private fun VideoPlayer(videoPath: String) {
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    val playerRef = remember { arrayOfNulls<VideoView>(1) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(
            factory = { context ->
                VideoView(context).also { view ->
                    playerRef[0] = view
                    view.setOnPreparedListener { player ->
                        player.isLooping = false
                        playbackError = null
                    }
                    view.setOnCompletionListener { playing = false }
                    view.setOnErrorListener { _, _, _ ->
                        playing = false
                        playbackError = "无法播放此视频"
                        true
                    }
                }
            },
            update = { view ->
                if (loadedPath != videoPath) {
                    view.stopPlayback()
                    playing = false
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
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
        )
        OmniPrimaryButton(
            text = if (playing) "暂停" else "播放",
            onClick = {
                playerRef[0]?.let { player ->
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        playbackError = null
                        player.start()
                        playing = true
                    }
                }
            },
            accent = OmniColors.VioletDeep
        )
        playbackError?.let { message ->
            Text(message, color = OmniColors.Rose, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VideoGalleryItem(
    video: VideoItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val title = videoDisplayName(video)
    ContentCard(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VideoThumbnail(video, title)
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = OmniColors.Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                VideoMetadata(video)
            }
            Icon(
                imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.PlayArrow,
                contentDescription = if (selected) "已载入播放器" else "载入播放器",
                tint = if (selected) OmniColors.Mint else OmniColors.Dim,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun VideoThumbnail(video: VideoItem, title: String) {
    val modifier = Modifier
        .width(104.dp)
        .height(78.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(Color.Black)
    val thumbnailPath = video.thumbnailPath
    if (thumbnailPath.isNullOrBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Movie,
                contentDescription = "$title 的视频缩略图不可用",
                tint = OmniColors.Dim,
                modifier = Modifier.size(28.dp)
            )
        }
    } else {
        OmniAsyncImage(
            path = thumbnailPath,
            contentDescription = "$title 的视频缩略图",
            modifier = modifier,
            maxDimension = 256
        )
    }
}

@Composable
private fun VideoMetadata(video: VideoItem) {
    val metadata = videoMetadata(video)
    if (metadata.isNotEmpty()) {
        Text(
            metadata,
            color = OmniColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
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
