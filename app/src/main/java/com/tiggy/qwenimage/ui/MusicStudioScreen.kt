package com.tiggy.qwenimage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.MusicStudioTab
import com.tiggy.qwenimage.data.MusicTrack
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

private val lyricSectionTags = listOf(
    "[Intro]", "[Verse]", "[Pre-Chorus]", "[Chorus]", "[Bridge]", "[Outro]", "[Instrumental]"
)

private val musicDurationChoices = listOf(30, 60, 90, 150)

@Composable
fun MusicStudioScreen(state: QwenUiState, viewModel: QwenViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        OmniTabs(
            items = MusicStudioTab.entries,
            selected = state.musicTab,
            accent = OmniColors.Violet,
            label = { it.label },
            onSelect = viewModel::selectMusicTab
        )
        Spacer(Modifier.height(12.dp))
        when (state.musicTab) {
            MusicStudioTab.Compose -> MusicComposeView(state, viewModel)
            MusicStudioTab.Playlist -> MusicPlaylistView(state, viewModel)
            MusicStudioTab.Score -> MusicScoreView(state)
        }
    }
}

@Composable
private fun MusicComposeView(state: QwenUiState, viewModel: QwenViewModel) {
    val settings = state.musicSettings
    val task = state.musicTask
    var showAdvanced by remember { mutableStateOf(false) }
    var seedText by remember(settings.seed) { mutableStateOf(settings.seed.toString()) }
    var guidanceText by remember(settings.guidanceScale) {
        mutableStateOf(settings.guidanceScale.toString())
    }
    val showPlaylistShortcut = !task.running && task.resultPath != null &&
        state.musicJobs.lastOrNull()?.state == "completed"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ContentCard(title = "曲目") {
                OmniTextField(
                    value = settings.title,
                    onValueChange = viewModel::setMusicTitle,
                    label = "标题",
                    placeholder = "未命名曲目",
                    modifier = Modifier.fillMaxWidth()
                )
                OmniTextField(
                    value = settings.style,
                    onValueChange = viewModel::setMusicStyle,
                    label = "风格",
                    placeholder = "例如：抒情流行，钢琴与女声",
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OmniTextField(
                    value = settings.lyrics,
                    onValueChange = viewModel::setMusicLyrics,
                    label = "歌词",
                    placeholder = "输入歌词，可用 [Verse]、[Chorus] 分段",
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("插入段落", color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(lyricSectionTags) { tag ->
                        TextButton(
                            onClick = {
                                val lyrics = if (settings.lyrics.isBlank()) {
                                    "$tag\n"
                                } else {
                                    "${settings.lyrics.trimEnd()}\n\n$tag\n"
                                }
                                viewModel.setMusicLyrics(lyrics)
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(
                                tag,
                                color = OmniColors.Violet,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        item {
            ContentCard(
                title = "节奏",
                trailing = {
                    Text("${settings.bpm} BPM", color = OmniColors.Mint, style = MaterialTheme.typography.labelLarge)
                }
            ) {
                Slider(
                    value = settings.bpm.toFloat().coerceIn(50f, 220f),
                    onValueChange = { viewModel.setMusicBpm(it.roundToInt()) },
                    valueRange = 50f..220f,
                    colors = SliderDefaults.colors(
                        thumbColor = OmniColors.Mint,
                        activeTrackColor = OmniColors.Mint,
                        inactiveTrackColor = OmniColors.ContentStrong
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "节奏 ${settings.bpm} BPM" }
                )
            }
        }

        item {
            ContentCard(title = "YuE2 工作站") {
                OmniTextField(
                    value = settings.serverUrl,
                    onValueChange = viewModel::setMusicServerUrl,
                    label = "工作站地址",
                    placeholder = "http://192.168.1.10:8999",
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "YuE2 在工作站运行，请填写可访问的服务地址。",
                    color = OmniColors.Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            OmniPrimaryButton(
                text = if (task.running) "正在生成" else "生成音乐",
                onClick = viewModel::startMusicGeneration,
                enabled = !task.running,
                busy = task.running,
                accent = OmniColors.VioletDeep
            )
        }

        item {
            ContentCard(title = "任务状态") {
                OmniTaskStatus(task = task, onCancel = viewModel::cancelMusicGeneration)
                if (showPlaylistShortcut) {
                    TextButton(
                        onClick = { viewModel.selectMusicTab(MusicStudioTab.Playlist) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            Icons.Outlined.LibraryMusic,
                            contentDescription = "打开曲库",
                            tint = OmniColors.Violet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("打开曲库")
                    }
                }
            }
        }

        item {
            ContentCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (showAdvanced) "收起高级参数" else "展开高级参数"
                        ) { showAdvanced = !showAdvanced },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "高级参数",
                        color = OmniColors.Ink,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (showAdvanced) "收起高级参数" else "展开高级参数",
                        tint = OmniColors.Muted
                    )
                }
                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("CoT 规划", color = OmniColors.Muted, style = MaterialTheme.typography.labelLarge)
                        OmniTabs(
                            items = CoTMode.entries,
                            selected = settings.cotMode,
                            accent = OmniColors.Violet,
                            label = { cotModeLabel(it) },
                            onSelect = viewModel::setMusicCotMode
                        )
                        Text(
                            cotModeSummary(settings.cotMode),
                            color = OmniColors.Dim,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OmniTextField(
                                value = seedText,
                                onValueChange = { value ->
                                    seedText = value
                                    value.toLongOrNull()?.let(viewModel::setMusicSeed)
                                },
                                label = "Seed",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    viewModel.setMusicSeed(Random.nextLong(100_000, 100_000_000))
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "随机生成 Seed",
                                    tint = OmniColors.Violet
                                )
                            }
                        }
                        Text(
                            "目标时长 · ${settings.targetDurationSec} 秒",
                            color = OmniColors.Muted,
                            style = MaterialTheme.typography.labelLarge
                        )
                        OmniTabs(
                            items = musicDurationChoices,
                            selected = settings.targetDurationSec,
                            accent = OmniColors.Mint,
                            label = { "$it 秒" },
                            onSelect = viewModel::setMusicTargetDuration
                        )
                        Text(
                            "ODE steps · ${settings.odeSteps}",
                            color = OmniColors.Muted,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = settings.odeSteps.toFloat().coerceIn(8f, 64f),
                            onValueChange = { viewModel.setMusicOdeSteps(it.roundToInt()) },
                            valueRange = 8f..64f,
                            colors = SliderDefaults.colors(
                                thumbColor = OmniColors.Violet,
                                activeTrackColor = OmniColors.Violet,
                                inactiveTrackColor = OmniColors.ContentStrong
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .semantics { contentDescription = "ODE steps ${settings.odeSteps}" }
                        )
                        OmniTextField(
                            value = guidanceText,
                            onValueChange = { value ->
                                guidanceText = value
                                value.toFloatOrNull()?.takeIf { it.isFinite() }?.let(viewModel::setMusicGuidanceScale)
                            },
                            label = "guidanceScale",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                OmniLogDisclosure(logs = task.logs)
            }
        }
    }
}

@Composable
private fun MusicPlaylistView(state: QwenUiState, viewModel: QwenViewModel) {
    val context = LocalContext.current
    val selectedTrack = state.activeMusicTrack
    val isSelectedLoaded = selectedTrack != null && selectedTrack.id == state.nowPlayingTrack?.id
    val isSelectedPlaying = isSelectedLoaded && state.isMusicPlaying
    val durationMs = if (isSelectedLoaded && state.musicDurationMs > 0) {
        state.musicDurationMs
    } else {
        trackDurationMs(selectedTrack)
    }
    val positionMs = if (isSelectedLoaded) state.musicPositionMs.coerceAtLeast(0) else 0
    val playDescription = when {
        selectedTrack == null -> "播放"
        isSelectedPlaying -> "暂停 ${selectedTrack.title}"
        isSelectedLoaded -> "继续播放 ${selectedTrack.title}"
        else -> "播放 ${selectedTrack.title}"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ContentCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            selectedTrack?.title?.ifBlank { "未命名曲目" } ?: "未选择曲目",
                            color = OmniColors.Ink,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            selectedTrack?.style?.ifBlank { "无风格描述" } ?: "从曲库选择或生成一首",
                            color = OmniColors.Muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selectedTrack != null) {
                        IconButton(
                            onClick = { shareMusicTrack(context, selectedTrack) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = "分享 ${selectedTrack.title}",
                                tint = OmniColors.Muted
                            )
                        }
                    }
                }
                if (selectedTrack != null) {
                    Text(
                        (if (isSelectedPlaying) "正在播放 · " else "") + musicTrackMetadata(selectedTrack),
                        color = if (isSelectedPlaying) OmniColors.Mint else OmniColors.Dim,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Slider(
                    value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction ->
                        if (isSelectedLoaded && durationMs > 0) {
                            viewModel.seekMusic((fraction * durationMs).roundToInt())
                        }
                    },
                    enabled = isSelectedLoaded && durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = OmniColors.Mint,
                        activeTrackColor = OmniColors.Mint,
                        inactiveTrackColor = OmniColors.ContentStrong,
                        disabledThumbColor = OmniColors.Dim,
                        disabledInactiveTrackColor = OmniColors.ContentStrong
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "播放进度" }
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatPlaybackTime(positionMs), color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (durationMs > 0) formatPlaybackTime(durationMs) else "--:--",
                        color = OmniColors.Dim,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = {
                            if (selectedTrack != null) viewModel.playMusic(selectedTrack)
                        },
                        enabled = selectedTrack != null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (selectedTrack == null) OmniColors.ContentStrong else OmniColors.VioletDeep
                            )
                    ) {
                        Icon(
                            if (isSelectedPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = playDescription,
                            tint = if (selectedTrack == null) OmniColors.Dim else OmniColors.Ink,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }

        item {
            Text(
                "曲库 (${state.musicPlaylist.size})",
                color = OmniColors.Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (state.musicPlaylist.isEmpty()) {
            item {
                OmniEmptyState(
                    icon = Icons.Outlined.LibraryMusic,
                    title = "暂无曲目",
                    subtitle = "生成后可在这里播放"
                )
            }
        } else {
            items(state.musicPlaylist, key = { it.id }) { track ->
                val isCurrent = state.nowPlayingTrack?.id == track.id
                MusicTrackRow(
                    track = track,
                    isSelected = state.activeMusicTrack?.id == track.id,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && state.isMusicPlaying,
                    onPlay = { viewModel.playMusic(track) },
                    onShowScore = {
                        viewModel.setActiveTrack(track)
                        viewModel.selectMusicTab(MusicStudioTab.Score)
                    },
                    onShare = { shareMusicTrack(context, track) }
                )
            }
        }
    }
}

@Composable
private fun MusicTrackRow(
    track: MusicTrack,
    isSelected: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShowScore: () -> Unit,
    onShare: () -> Unit
) {
    val actionLabel = when {
        isPlaying -> "暂停"
        isCurrent -> "继续播放"
        else -> "播放"
    }
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onPlay),
        shape = shape,
        color = if (isSelected) OmniColors.Violet.copy(alpha = 0.12f) else OmniColors.Content,
        border = BorderStroke(
            1.dp,
            if (isSelected) OmniColors.Violet.copy(alpha = 0.45f) else OmniColors.Border
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isPlaying) OmniColors.Mint.copy(alpha = 0.22f)
                        else OmniColors.Violet.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = "$actionLabel ${track.title}",
                    tint = if (isCurrent) OmniColors.Mint else OmniColors.Violet
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title.ifBlank { "未命名曲目" },
                    color = OmniColors.Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    (if (isPlaying) "正在播放 · " else "") + musicTrackMetadata(track),
                    color = if (isPlaying) OmniColors.Mint else OmniColors.Dim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!track.abcScore.isNullOrBlank()) {
                IconButton(onClick = onShowScore, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Piano,
                        contentDescription = "查看 ${track.title} 的乐谱",
                        tint = OmniColors.Mint
                    )
                }
            }
            IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "分享 ${track.title}",
                    tint = OmniColors.Muted
                )
            }
        }
    }
}

@Composable
private fun MusicScoreView(state: QwenUiState) {
    val context = LocalContext.current
    val track = state.activeMusicTrack
    val score = track?.abcScore?.takeIf { it.isNotBlank() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            track == null -> item {
                OmniEmptyState(
                    icon = Icons.Outlined.Piano,
                    title = "未选择曲目",
                    subtitle = "请先在曲库选择曲目"
                )
            }
            score == null -> item {
                OmniEmptyState(
                    icon = Icons.Outlined.Piano,
                    title = "当前曲目没有乐谱",
                    subtitle = "没有可显示的 ABC 内容"
                )
            }
            else -> item {
                val abcKey = abcHeaderValue(score, "K")
                val abcMeter = abcHeaderValue(score, "M")
                val scoreDetails = listOfNotNull(
                    abcKey?.let { "调号：$it" },
                    abcMeter?.let { "拍号：$it" }
                ).joinToString(" · ")

                ContentCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title.ifBlank { "未命名曲目" },
                                color = OmniColors.Ink,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (scoreDetails.isNotEmpty()) {
                                Text(
                                    scoreDetails,
                                    color = OmniColors.Mint,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        IconButton(
                            onClick = { copyAbcScore(context, score) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "复制 ${track.title} 的 ABC 乐谱",
                                tint = OmniColors.Violet
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            score,
                            color = OmniColors.Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

private fun cotModeLabel(mode: CoTMode): String = when (mode) {
    CoTMode.Melody -> "旋律"
    CoTMode.Full -> "旋律与和弦"
    CoTMode.Off -> "关闭"
}

private fun cotModeSummary(mode: CoTMode): String = when (mode) {
    CoTMode.Melody -> "请求 ABC 旋律规划"
    CoTMode.Full -> "请求旋律与和弦规划"
    CoTMode.Off -> "跳过符号规划"
}

private fun musicTrackMetadata(track: MusicTrack): String = listOfNotNull(
    track.bpm.takeIf { it > 0 }?.let { "$it BPM" },
    track.durationSec.takeIf { it.isFinite() && it > 0f }?.let { formatMediaDuration(it) },
    track.sampleRate.takeIf { it > 0 }?.let { "$it Hz" }
).joinToString(" · ").ifBlank { "音频信息未知" }

private fun trackDurationMs(track: MusicTrack?): Int {
    val seconds = track?.durationSec ?: return 0
    return if (seconds.isFinite() && seconds > 0f) (seconds * 1000f).toInt() else 0
}

private fun formatPlaybackTime(timeMs: Int): String {
    val totalSeconds = timeMs.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun abcHeaderValue(score: String, header: String): String? = score.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith("$header:") }
    .map { it.substringAfter(':').trim() }
    .firstOrNull { it.isNotEmpty() }

private fun copyAbcScore(context: Context, score: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ABC 乐谱", score))
    }.onSuccess {
        Toast.makeText(context, "ABC 已复制", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "复制失败，请重试", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMusicTrack(context: Context, track: MusicTrack) {
    val file = File(track.audioPath)
    if (!file.isFile) {
        Toast.makeText(context, "音频文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val mimeType = audioMimeType(file.extension)
    if (mimeType == null) {
        Toast.makeText(context, "无法识别音频格式", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        shareMediaFile(context, file, mimeType, "分享音乐")
    }.onFailure {
        Toast.makeText(context, "分享失败，请检查文件和分享应用", Toast.LENGTH_SHORT).show()
    }
}

private fun audioMimeType(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    else -> null
}
