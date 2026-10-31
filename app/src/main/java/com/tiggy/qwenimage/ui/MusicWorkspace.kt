package com.tiggy.qwenimage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
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
import com.tiggy.qwenimage.data.MusicJob
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicTrack
import com.tiggy.qwenimage.data.TaskUiState
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

private val lyricSectionTags = listOf(
    "[Intro]", "[Verse]", "[Pre-Chorus]", "[Chorus]", "[Bridge]", "[Outro]", "[Instrumental]"
)

private val musicDurationChoices = listOf(30, 60, 90, 150)

/** Text-only starting points; they fill the composer and lyrics but ship no audio. */
private data class MusicTemplate(val name: String, val style: String, val bpm: Int, val lyrics: String)

private val musicTemplates = listOf(
    MusicTemplate(
        "抒情流行", "emotional Mandarin pop ballad, warm female vocal, piano and strings, soft drums", 88,
        "[Verse]\n晚风吹过街角的灯\n城市慢慢安静下来\n我把今天写进旋律\n留给明天的自己\n\n[Chorus]\n唱吧 让心跳跟着节拍\n唱吧 把烦恼留在窗外"
    ),
    MusicTemplate(
        "城市流行", "city pop, nu-disco groove, bright male vocal, funky bass, electric piano", 118,
        "[Verse]\n霓虹亮起的路口\n车流像河一样温柔\n\n[Chorus]\n跟着节奏 向前走\n今晚的风刚刚好"
    ),
    MusicTemplate(
        "电子偶像", "Vocaloid style J-pop, bright synthetic female vocal, sparkling synths, fast electronic beat", 136,
        "[Verse]\n電子の海を飛び越えて\n新しい朝へ走り出す\n\n[Chorus]\n今日も元気に歌おう\n光のリズムに乗って"
    ),
    MusicTemplate(
        "摇滚", "energetic rock, distorted guitars, powerful male vocal, driving drums", 150,
        "[Verse]\n把旧地图揉成一团\n脚下的路自己来选\n\n[Chorus]\n大声唱 别回头\n让世界听见我们"
    ),
    MusicTemplate(
        "国风", "Chinese traditional fusion, guzheng and dizi, ethereal female vocal, gentle percussion", 76,
        "[Verse]\n青山远 白云闲\n一叶小舟过江南\n\n[Chorus]\n风吹过 千年月\n照见故人旧时颜"
    ),
    MusicTemplate("纯音乐", "instrumental lo-fi hip hop, mellow piano, vinyl texture, soft beat", 80, "[Instrumental]")
)

private enum class MusicSheet { Lyrics, Params }

private sealed interface MusicBlock {
    val key: String
    val createdAt: Long

    data class Done(val track: MusicTrack) : MusicBlock {
        override val key: String = "track-${track.id}"
        override val createdAt: Long = track.createdAt
    }

    data class Pending(val job: MusicJob) : MusicBlock {
        override val key: String = "job-${job.id}"
        override val createdAt: Long = job.createdAt
    }
}

@Composable
fun MusicWorkspace(
    state: QwenUiState,
    viewModel: QwenViewModel,
    onMenu: (() -> Unit)?,
    onSettings: () -> Unit
) {
    val settings = state.musicSettings
    val task = state.musicTask
    var sheet by remember { mutableStateOf<MusicSheet?>(null) }
    var scoreTrack by remember { mutableStateOf<MusicTrack?>(null) }
    val listState = rememberLazyListState()
    val blocks = remember(state.musicPlaylist, state.musicJobs) {
        buildList<MusicBlock> {
            addAll(state.musicPlaylist.map { MusicBlock.Done(it) })
            addAll(state.musicJobs.filter { it.state != "completed" && it.state != "cancelled" }.map { MusicBlock.Pending(it) })
        }.sortedBy { it.createdAt }
    }
    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty()) listState.scrollToItem(blocks.size, 100_000)
    }
    val jobErrors = state.musicJobs.mapNotNull { it.error }
    val composerError = task.error?.takeIf { !task.running && it !in jobErrors }

    WorkspaceScaffold(
        title = "音乐",
        subtitle = "YuE2 · 工作站",
        onMenu = onMenu,
        composer = { haze ->
            val status: (@Composable () -> Unit)? = if (composerError != null) ({
                Text(composerError, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
            }) else null
            OmniComposer(
                hazeState = haze,
                value = settings.style,
                onValueChange = viewModel::setMusicStyle,
                placeholder = "风格，例如 抒情流行，钢琴与女声",
                onSend = viewModel::startMusicGeneration,
                sendEnabled = true,
                running = task.running,
                onStop = viewModel::cancelMusicGeneration,
                status = status,
                tools = {
                    ComposerChip("歌词", onClick = { sheet = MusicSheet.Lyrics }, icon = Icons.Outlined.EditNote,
                        selected = settings.lyrics.isNotBlank())
                    ComposerChip("${settings.bpm} BPM", onClick = { sheet = MusicSheet.Params })
                    ComposerChip("", onClick = { sheet = MusicSheet.Params }, icon = Icons.Outlined.Tune, contentDescription = "参数")
                }
            )
        }
    ) { inner ->
        if (blocks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("描述一段音乐", color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium)
                    MusicWorkstationNote(onSettings)
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
                item(key = "workstation-note") {
                    ReadingColumn { MusicWorkstationNote(onSettings) }
                }
                items(blocks, key = { it.key }) { block ->
                    ReadingColumn {
                        when (block) {
                            is MusicBlock.Done -> {
                                val track = block.track
                                val loaded = state.nowPlayingTrack?.id == track.id
                                MusicTrackBlock(
                                    track = track,
                                    loaded = loaded,
                                    playing = loaded && state.isMusicPlaying,
                                    positionMs = if (loaded) state.musicPositionMs else 0,
                                    durationMs = if (loaded && state.musicDurationMs > 0) state.musicDurationMs else musicTrackDurationMs(track),
                                    onPlay = { viewModel.playMusic(track) },
                                    onSeek = { viewModel.seekMusic(it) },
                                    onScore = {
                                        viewModel.setActiveTrack(track)
                                        scoreTrack = track
                                    }
                                )
                            }
                            is MusicBlock.Pending -> {
                                val job = block.job
                                MusicJobBlock(
                                    job = job,
                                    active = task.running && state.activeTask?.id == job.id,
                                    latest = job.id == state.musicJobs.lastOrNull()?.id,
                                    task = task,
                                    onCancel = viewModel::cancelMusicGeneration
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        MusicSheet.Lyrics -> MusicLyricsSheet(settings, viewModel) { sheet = null }
        MusicSheet.Params -> MusicParamsSheet(settings, viewModel) { sheet = null }
        null -> Unit
    }
    scoreTrack?.let { track -> MusicScoreSheet(track) { scoreTrack = null } }
}

@Composable
private fun MusicWorkstationNote(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("使用工作站 · 地址在设置中", color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("设置") }
    }
}

@Composable
private fun MusicTrackBlock(
    track: MusicTrack,
    loaded: Boolean,
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    onPlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onScore: () -> Unit
) {
    val context = LocalContext.current
    val title = track.title.ifBlank { "未命名曲目" }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (track.title.isNotBlank() && track.title != "未命名曲目") {
            Text(title, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp))
        }
        RequestBubble(track.style.ifBlank { title })
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = OmniColors.Surface,
            border = BorderStroke(0.5.dp, OmniColors.Line)
        ) {
            Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = onPlay, shape = CircleShape, color = OmniColors.Accent, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                if (playing) "暂停 $title" else "播放 $title",
                                tint = OmniColors.OnAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = OmniColors.Ink, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(musicTrackMetadata(track), color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!track.abcScore.isNullOrBlank()) {
                        IconButton(onClick = onScore, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Piano, "查看 $title 的乐谱", tint = OmniColors.Muted, modifier = Modifier.size(22.dp))
                        }
                    }
                    IconButton(onClick = { shareMusicTrack(context, track) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Share, "分享 $title", tint = OmniColors.Muted, modifier = Modifier.size(22.dp))
                    }
                }
                if (loaded) {
                    MusicPlaybackBar(positionMs, durationMs, onSeek, Modifier.padding(end = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun MusicPlaybackBar(positionMs: Int, durationMs: Int, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { if (durationMs > 0) onSeek((it * durationMs).roundToInt()) }
                dragFraction = null
            },
            enabled = durationMs > 0,
            colors = musicSliderColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "播放进度" }
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatPlaybackTime((fraction * durationMs).roundToInt()), color = OmniColors.Dim,
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) formatPlaybackTime(durationMs) else "--:--", color = OmniColors.Dim,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MusicJobBlock(
    job: MusicJob,
    active: Boolean,
    latest: Boolean,
    task: TaskUiState,
    onCancel: () -> Unit
) {
    val failed = job.state == "failed"
    val title = job.settings.title.trim()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotEmpty()) {
            Text(title, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp))
        }
        RequestBubble(job.settings.style.ifBlank { job.title })
        if (active && !failed) {
            TaskLine(task, onCancel = onCancel)
        } else {
            TaskLine(TaskUiState(status = job.stageDescription, error = job.error))
        }
        if (latest) OmniLogDisclosure(task.logs)
    }
}

@Composable
private fun MusicLyricsSheet(settings: MusicSettings, viewModel: QwenViewModel, onDismiss: () -> Unit) {
    OmniSheet("歌词", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(musicTemplates) { template ->
                    ComposerChip(
                        template.name,
                        onClick = {
                            viewModel.setMusicStyle(template.style)
                            viewModel.setMusicBpm(template.bpm)
                            viewModel.setMusicLyrics(template.lyrics)
                        },
                        selected = settings.style == template.style
                    )
                }
            }
            OmniTextField(
                value = settings.title,
                onValueChange = viewModel::setMusicTitle,
                label = "标题",
                placeholder = "未命名曲目",
                modifier = Modifier.fillMaxWidth()
            )
            OmniTextField(
                value = settings.lyrics,
                onValueChange = viewModel::setMusicLyrics,
                label = "歌词",
                placeholder = "输入歌词，可用 [Verse]、[Chorus] 分段",
                minLines = 8,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth()
            )
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(lyricSectionTags) { tag ->
                    TextButton(
                        onClick = {
                            val lyrics = if (settings.lyrics.isBlank()) "$tag\n" else "${settings.lyrics.trimEnd()}\n\n$tag\n"
                            viewModel.setMusicLyrics(lyrics)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(tag, color = OmniColors.Accent, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicParamsSheet(settings: MusicSettings, viewModel: QwenViewModel, onDismiss: () -> Unit) {
    var seedText by remember(settings.seed) { mutableStateOf(settings.seed.toString()) }
    var guidanceText by remember(settings.guidanceScale) { mutableStateOf(settings.guidanceScale.toString()) }
    OmniSheet("参数", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MusicSliderRow("节奏", "${settings.bpm} BPM", settings.bpm, 50..220) { viewModel.setMusicBpm(it) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CoT 规划", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                OmniTabs(
                    items = CoTMode.entries,
                    selected = settings.cotMode,
                    label = { musicCotLabel(it) },
                    onSelect = viewModel::setMusicCotMode
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("目标时长", color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                OmniTabs(
                    items = musicDurationChoices,
                    selected = settings.targetDurationSec,
                    label = { "$it 秒" },
                    onSelect = viewModel::setMusicTargetDuration
                )
            }
            MusicSliderRow("ODE 步数", "${settings.odeSteps}", settings.odeSteps, 8..64) { viewModel.setMusicOdeSteps(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniTextField(
                    value = seedText,
                    onValueChange = { value ->
                        seedText = value
                        value.toLongOrNull()?.let(viewModel::setMusicSeed)
                    },
                    label = "随机种子",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { viewModel.setMusicSeed(Random.nextLong(100_000, 100_000_000)) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, "随机种子", tint = OmniColors.Muted)
                }
            }
            OmniTextField(
                value = guidanceText,
                onValueChange = { value ->
                    guidanceText = value
                    value.toFloatOrNull()?.takeIf { it.isFinite() }?.let(viewModel::setMusicGuidanceScale)
                },
                label = "引导强度",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MusicSliderRow(label: String, valueLabel: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, color = OmniColors.Ink, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = musicSliderColors(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label $valueLabel" }
        )
    }
}

@Composable
private fun musicSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = OmniColors.Accent,
    activeTrackColor = OmniColors.Accent,
    inactiveTrackColor = OmniColors.Selected,
    disabledThumbColor = OmniColors.Dim,
    disabledActiveTrackColor = OmniColors.Dim,
    disabledInactiveTrackColor = OmniColors.Selected
)

@Composable
private fun MusicScoreSheet(track: MusicTrack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val score = track.abcScore.orEmpty()
    val details = listOfNotNull(
        abcHeaderValue(score, "K")?.let { "调号 $it" },
        abcHeaderValue(score, "M")?.let { "拍号 $it" }
    ).joinToString(" · ")
    OmniSheet("乐谱", onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title.ifBlank { "未命名曲目" }, color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) {
                    Text(details, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = { copyAbcScore(context, score) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制")
            }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(max = 420.dp)
                .clip(RoundedCornerShape(10.dp)).background(OmniColors.Field)
                .verticalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Text(score, color = OmniColors.Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private fun musicCotLabel(mode: CoTMode): String = when (mode) {
    CoTMode.Melody -> "旋律"
    CoTMode.Full -> "旋律与和弦"
    CoTMode.Off -> "关闭"
}

private fun musicTrackMetadata(track: MusicTrack): String = listOfNotNull(
    track.durationSec.takeIf { it.isFinite() && it > 0f }?.let { formatMediaDuration(it) },
    track.bpm.takeIf { it > 0 }?.let { "$it BPM" }
).joinToString(" · ").ifBlank { "音频信息未知" }

private fun musicTrackDurationMs(track: MusicTrack): Int {
    val seconds = track.durationSec
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
    val mimeType = musicAudioMimeType(file.extension)
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

private fun musicAudioMimeType(extension: String): String? = when (extension.lowercase(Locale.ROOT)) {
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    else -> null
}
