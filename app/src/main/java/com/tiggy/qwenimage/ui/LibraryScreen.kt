package com.tiggy.qwenimage.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.AppDomain
import com.tiggy.qwenimage.data.GenerationJob
import com.tiggy.qwenimage.data.MusicTrack
import com.tiggy.qwenimage.data.VideoItem
import java.io.File

private enum class LibraryTab(val label: String) { Images("图像"), Music("音乐"), Videos("视频") }

private data class MenuAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** The work directory: finished images, tracks and videos, nothing else. */
@Composable
fun LibraryScreen(
    state: QwenUiState,
    viewModel: QwenViewModel,
    onMenu: (() -> Unit)?,
    onPreview: (String) -> Unit,
    onOpenWorkspace: (AppDomain) -> Unit
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Images) }
    var deleteImage by remember { mutableStateOf<GenerationJob?>(null) }
    var deleteTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var deleteVideo by remember { mutableStateOf<VideoItem?>(null) }

    val images = remember(state.jobs) {
        state.jobs
            .filter { it.state == "done" && it.outputPath?.let { path -> File(path).isFile } == true }
            .sortedByDescending { it.createdAt }
    }
    val videos = remember(state.videoGallery) {
        state.videoGallery.filter { it.fileExists() }.sortedByDescending { it.createdAt }
    }

    WorkspaceScaffold(title = "工作目录", onMenu = onMenu) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gridColumns = if (maxWidth >= 600.dp) 3 else 2
            key(tab) {
                when (tab) {
                    LibraryTab.Images -> LibraryGrid(gridColumns, padding, tab, { tab = it }, rowSpacing = 14.dp) {
                        if (images.isEmpty()) {
                            emptyItem(Icons.Outlined.Image, "还没有图像")
                        } else {
                            items(images, key = { it.id }) { job ->
                                val path = job.outputPath.orEmpty()
                                val prompt = job.settings.prompt
                                    .ifBlank { job.mossSettings?.prompt.orEmpty() }
                                    .ifBlank { "图像" }
                                MediaCell(
                                    caption = prompt,
                                    detail = null,
                                    onOpen = { onPreview(path) },
                                    actions = listOf(
                                        MenuAction("继续编辑") {
                                            viewModel.editImage(path)
                                            onOpenWorkspace(AppDomain.ImageStudio)
                                        },
                                        MenuAction("生成视频") {
                                            viewModel.useImageAsVideoFirstFrame(path)
                                            onOpenWorkspace(AppDomain.VideoStudio)
                                        },
                                        MenuAction("分享") {
                                            runCatching { shareMediaFile(context, File(path), "image/png", "分享图像") }
                                        },
                                        MenuAction("删除", danger = true) { deleteImage = job }
                                    )
                                ) {
                                    OmniAsyncImage(path, prompt, Modifier.fillMaxSize(), maxDimension = 512,
                                        contentScale = ContentScale.Crop)
                                }
                            }
                        }
                    }

                    LibraryTab.Music -> LibraryGrid(1, padding, tab, { tab = it }, rowSpacing = 0.dp) {
                        if (state.musicPlaylist.isEmpty()) {
                            emptyItem(Icons.Outlined.LibraryMusic, "还没有音乐")
                        } else {
                            items(state.musicPlaylist, key = { it.id }) { track ->
                                TrackRow(
                                    track = track,
                                    playing = state.isMusicPlaying && state.nowPlayingTrack?.id == track.id,
                                    onPlay = { viewModel.playMusic(track) },
                                    onShare = {
                                        runCatching {
                                            shareMediaFile(context, File(track.audioPath), audioMimeType(track.audioPath), "分享音乐")
                                        }
                                    },
                                    onDelete = if (track.id.startsWith("preset_")) null else ({ deleteTrack = track })
                                )
                            }
                        }
                    }

                    LibraryTab.Videos -> LibraryGrid(gridColumns, padding, tab, { tab = it }, rowSpacing = 14.dp) {
                        if (videos.isEmpty()) {
                            emptyItem(Icons.Outlined.VideoLibrary, "还没有视频")
                        } else {
                            items(videos, key = { it.id }) { video ->
                                val open = {
                                    viewModel.setActiveVideo(video)
                                    onOpenWorkspace(AppDomain.VideoStudio)
                                }
                                MediaCell(
                                    caption = video.prompt.ifBlank { "视频" },
                                    detail = "${formatMediaDuration(video.durationSec)} · ${video.resolution}",
                                    onOpen = open,
                                    actions = listOf(
                                        MenuAction("打开") { open() },
                                        MenuAction("分享") {
                                            runCatching {
                                                shareMediaFile(context, File(video.videoPath), video.mimeType(), "分享视频")
                                            }
                                        },
                                        MenuAction("删除", danger = true) { deleteVideo = video }
                                    )
                                ) {
                                    val thumbnail = video.thumbnailPath
                                    if (thumbnail != null) {
                                        OmniAsyncImage(thumbnail, video.prompt, Modifier.fillMaxSize(), maxDimension = 512,
                                            contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(Icons.Outlined.Movie, null, tint = OmniColors.Dim,
                                            modifier = Modifier.align(Alignment.Center).size(28.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteImage?.let { job ->
        ConfirmDeleteDialog("删除这张图像？", "文件会从手机中移除。",
            onConfirm = { viewModel.deleteImageJob(job.id); deleteImage = null },
            onDismiss = { deleteImage = null })
    }
    deleteTrack?.let { track ->
        ConfirmDeleteDialog("删除「${track.title}」？", "音频文件会从手机中移除。",
            onConfirm = { viewModel.deleteMusicTrack(track); deleteTrack = null },
            onDismiss = { deleteTrack = null })
    }
    deleteVideo?.let { video ->
        ConfirmDeleteDialog("删除这个视频？", "视频文件会从手机中移除。",
            onConfirm = { viewModel.deleteVideo(video); deleteVideo = null },
            onDismiss = { deleteVideo = null })
    }
}

@Composable
private fun LibraryGrid(
    columns: Int,
    padding: PaddingValues,
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    rowSpacing: Dp,
    content: LazyGridScope.() -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.widthIn(max = 960.dp).fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(rowSpacing)
        ) {
            item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(bottom = 6.dp)) {
                    OmniTabs(items = LibraryTab.entries, selected = tab, label = { it.label }, onSelect = onTab)
                }
            }
            content()
        }
    }
}

private fun LazyGridScope.emptyItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
        OmniEmptyState(icon, title)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    caption: String,
    detail: String?,
    onOpen: () -> Unit,
    actions: List<MenuAction>,
    thumbnail: @Composable BoxScope.() -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(OmniColors.Field)
                .combinedClickable(
                    onClickLabel = "打开",
                    onClick = onOpen,
                    onLongClickLabel = "更多操作",
                    onLongClick = { menu = true }
                )
        ) {
            thumbnail()
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(OmniColors.Background.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.MoreHoriz, "更多操作", tint = OmniColors.Ink, modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = OmniColors.Raised) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label, color = if (action.danger) OmniColors.Danger else OmniColors.Ink) },
                            onClick = { menu = false; action.onClick() }
                        )
                    }
                }
            }
        }
        Text(caption, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (detail != null) {
            Text(detail, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TrackRow(
    track: MusicTrack,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (playing) "暂停 ${track.title}" else "播放 ${track.title}", tint = OmniColors.Accent)
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(track.title, color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatMediaDuration(track.durationSec)} · ${track.bpm} BPM", color = OmniColors.Dim,
                    style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Share, "分享 ${track.title}", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, "删除 ${track.title}", tint = OmniColors.Muted,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = OmniColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = OmniColors.Raised
    )
}

private fun audioMimeType(path: String): String = when (File(path).extension.lowercase()) {
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "m4a", "aac" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    else -> "audio/*"
}
