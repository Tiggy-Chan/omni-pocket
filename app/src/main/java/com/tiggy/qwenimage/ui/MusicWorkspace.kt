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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Piano
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
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.R
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.MusicJob
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicTrack
import com.tiggy.qwenimage.data.TaskUiState
import com.tiggy.qwenimage.data.formatBytesCompact
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

private val lyricSectionTags = listOf(
    "[Intro]", "[Verse]", "[Pre-Chorus]", "[Chorus]", "[Bridge]", "[Outro]", "[Instrumental]"
)

private val musicDurationChoices = listOf(15, 30, 60, 90)

/** Text-only starting points; they fill the composer and lyrics but ship no audio. */
private data class MusicTemplate(@StringRes val nameRes: Int, val style: String, val bpm: Int, val lyrics: String)

private val musicTemplates = listOf(
    MusicTemplate(
        R.string.music_tpl_ballad, "emotional Mandarin pop ballad, warm female vocal, piano and strings, soft drums", 88,
        "[Verse]\n晚风吹过街角的灯\n城市慢慢安静下来\n我把今天写进旋律\n留给明天的自己\n\n[Chorus]\n唱吧 让心跳跟着节拍\n唱吧 把烦恼留在窗外"
    ),
    MusicTemplate(
        R.string.music_tpl_citypop, "city pop, nu-disco groove, bright male vocal, funky bass, electric piano", 118,
        "[Verse]\n霓虹亮起的路口\n车流像河一样温柔\n\n[Chorus]\n跟着节奏 向前走\n今晚的风刚刚好"
    ),
    MusicTemplate(
        R.string.music_tpl_vocaloid, "Vocaloid style J-pop, bright synthetic female vocal, sparkling synths, fast electronic beat", 136,
        "[Verse]\n電子の海を飛び越えて\n新しい朝へ走り出す\n\n[Chorus]\n今日も元気に歌おう\n光のリズムに乗って"
    ),
    MusicTemplate(
        R.string.music_tpl_rock, "energetic rock, distorted guitars, powerful male vocal, driving drums", 150,
        "[Verse]\n把旧地图揉成一团\n脚下的路自己来选\n\n[Chorus]\n大声唱 别回头\n让世界听见我们"
    ),
    MusicTemplate(
        R.string.music_tpl_guofeng, "Chinese traditional fusion, guzheng and dizi, ethereal female vocal, gentle percussion", 76,
        "[Verse]\n青山远 白云闲\n一叶小舟过江南\n\n[Chorus]\n风吹过 千年月\n照见故人旧时颜"
    ),
    MusicTemplate(R.string.music_tpl_instrumental, "instrumental lo-fi hip hop, mellow piano, vinyl texture, soft beat", 80, "[Instrumental]")
)

private enum class MusicSheet { Model, Lyrics, Params }

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
    onModels: () -> Unit
) {
    val settings = state.musicSettings
    val task = state.musicTask
    val activeCustomModel = state.customMusicModels.firstOrNull { it.id == settings.modelId }
    val modelLabel = activeCustomModel?.name ?: "YuE2-3B"
    // Stats model files, so check once per visit and after each job or model change rather than on every recomposition.
    val missing = remember(task.running, state.yue2ModelStatus, settings.modelId, state.customMusicModels) {
        viewModel.musicMissingComponents()
    }
    val ready = missing.isEmpty()
    var sheet by remember { mutableStateOf<MusicSheet?>(null) }
    var scoreTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var pendingDelete by remember { mutableStateOf<MusicTrack?>(null) }
    val listState = rememberLazyListState()
    val blocks = remember(state.musicPlaylist, state.musicJobs) {
        buildList<MusicBlock> {
            addAll(state.musicPlaylist.map { MusicBlock.Done(it) })
            addAll(state.musicJobs.filter { it.state != "completed" && it.state != "cancelled" }.map { MusicBlock.Pending(it) })
        }.sortedBy { it.createdAt }
    }
    val lead = if (ready) 0 else 1
    LaunchedEffect(blocks.size) {
        if (blocks.isNotEmpty()) listState.scrollToItem(blocks.size - 1 + lead, 100_000)
    }
    val jobErrors = state.musicJobs.mapNotNull { it.error }
    val composerError = task.error?.takeIf { !task.running && it !in jobErrors }

    WorkspaceScaffold(
        title = tr(R.string.nav_music),
        subtitle = if (ready) tr(R.string.common_on_device_tag, modelLabel) else tr(R.string.common_model_not_ready_tag, modelLabel),
        onMenu = onMenu,
        composer = { haze ->
            val status: (@Composable () -> Unit)? = if (composerError != null) ({
                Text(composerError, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall)
            }) else null
            OmniComposer(
                hazeState = haze,
                value = settings.style,
                onValueChange = viewModel::setMusicStyle,
                placeholder = tr(R.string.music_style_hint),
                onSend = viewModel::startMusicGeneration,
                sendEnabled = ready && settings.style.isNotBlank(),
                running = task.running,
                onStop = viewModel::cancelMusicGeneration,
                status = status,
                tools = {
                    ComposerChip(modelLabel.take(12), onClick = { sheet = MusicSheet.Model })
                    ComposerChip(tr(R.string.music_lyrics), onClick = { sheet = MusicSheet.Lyrics }, icon = Icons.Outlined.EditNote,
                        selected = settings.lyrics.isNotBlank())
                    ComposerChip(tr(R.string.common_seconds_value, settings.targetDurationSec), onClick = { sheet = MusicSheet.Params })
                    ComposerChip("", onClick = { sheet = MusicSheet.Params }, icon = Icons.Outlined.Tune, contentDescription = tr(R.string.common_parameters))
                }
            )
        }
    ) { inner ->
        if (blocks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr(R.string.music_empty_title), color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium)
                    if (!ready) MusicModelNote(modelLabel, missing, onModels)
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
                        ReadingColumn { MusicModelNote(modelLabel, missing, onModels) }
                    }
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
                                    },
                                    onReuse = { viewModel.reuseMusicTrack(track) },
                                    onDelete = { pendingDelete = track }
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
        MusicSheet.Model -> MusicModelSheet(
            state = state,
            onSelect = { modelId ->
                viewModel.selectMusicModel(modelId)
                sheet = null
            },
            onManage = {
                sheet = null
                onModels()
            },
            onDismiss = { sheet = null }
        )
        MusicSheet.Lyrics -> MusicLyricsSheet(settings, viewModel) { sheet = null }
        MusicSheet.Params -> MusicParamsSheet(settings, viewModel) { sheet = null }
        null -> Unit
    }
    scoreTrack?.let { track -> MusicScoreSheet(track) { scoreTrack = null } }
    pendingDelete?.let { track ->
        val untitled = tr(R.string.music_untitled)
        val displayTitle = if (track.title.isBlank() || track.title == "未命名曲目") untitled else track.title
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr(R.string.music_delete_title)) },
            text = { Text(tr(R.string.music_delete_body, displayTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.deleteMusicTrack(track)
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
private fun MusicModelNote(modelLabel: String, missing: List<String>, onModels: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.common_model_not_ready_tag, modelLabel), color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onModels, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(R.string.common_go_download)) }
        }
        Text(
            tr(R.string.music_missing_hint, missing.joinToString(", ")),
            color = OmniColors.Dim,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MusicModelSheet(
    state: QwenUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentLabel = tr(R.string.common_current)
    OmniSheet(tr(R.string.music_models_title), onDismiss) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SettingRow(
                title = tr(R.string.common_builtin_tag, "YuE2-3B"),
                detail = if (state.yue2ModelStatus?.ready == true) tr(R.string.music_builtin_ready) else tr(R.string.common_not_downloaded),
                onClick = { onSelect("") }
            ) {
                if (state.musicSettings.modelId.isBlank()) {
                    Icon(Icons.Outlined.Check, currentLabel, tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
                }
            }
            if (state.customMusicModels.isNotEmpty()) {
                SectionLabel(tr(R.string.common_community_downloaded))
                state.customMusicModels.forEach { model ->
                    SettingRow(
                        title = model.name,
                        detail = "${model.repo} · ${formatBytesCompact(model.totalBytes)}",
                        onClick = { onSelect(model.id) }
                    ) {
                        if (state.musicSettings.modelId == model.id) {
                            Icon(Icons.Outlined.Check, currentLabel, tint = OmniColors.Accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            SettingRow(
                title = tr(R.string.music_search_more),
                detail = tr(R.string.music_search_more_sub),
                onClick = onManage
            )
        }
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
    onScore: () -> Unit,
    onReuse: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val untitled = tr(R.string.music_untitled)
    val shareTitle = tr(R.string.music_share_title)
    val pauseLabel = tr(R.string.common_pause)
    val playLabel = tr(R.string.common_play)
    val shareLabel = tr(R.string.common_share)
    val deleteLabel = tr(R.string.common_delete)
    val scoreLabel = tr(R.string.music_score)
    val title = if (track.title.isBlank() || track.title == "未命名曲目") untitled else track.title
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (track.title.isNotBlank() && track.title != "未命名曲目" && track.title != untitled) {
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
                                if (playing) "$pauseLabel $title" else "$playLabel $title",
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
                        IconButton(onClick = onScore, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Piano, "$scoreLabel · $title", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = onReuse, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Replay, tr(R.string.music_reuse), tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { shareMusicTrack(context, track, shareTitle) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Share, "$shareLabel $title", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                    }
                    if (!track.id.startsWith("preset_")) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, "$deleteLabel $title", tint = OmniColors.Muted, modifier = Modifier.size(20.dp))
                        }
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
    val progressCd = tr(R.string.music_playback_progress)
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
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressCd }
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
    val localized = LocalLocalizedContext.current
    OmniSheet(tr(R.string.music_lyrics), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(musicTemplates) { template ->
                    ComposerChip(
                        localized.getString(template.nameRes),
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
                label = tr(R.string.music_title_label),
                placeholder = tr(R.string.music_untitled),
                modifier = Modifier.fillMaxWidth()
            )
            OmniTextField(
                value = settings.lyrics,
                onValueChange = viewModel::setMusicLyrics,
                label = tr(R.string.music_lyrics),
                placeholder = tr(R.string.music_lyrics_hint),
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
    val localized = LocalLocalizedContext.current
    val cotLabels = mapOf(
        CoTMode.Melody to CoTMode.Melody.localizedLabel(),
        CoTMode.Full to CoTMode.Full.localizedLabel(),
        CoTMode.Off to CoTMode.Off.localizedLabel()
    )
    var seedText by remember(settings.seed) { mutableStateOf(settings.seed.toString()) }
    var guidanceText by remember(settings.guidanceScale) { mutableStateOf(settings.guidanceScale.toString()) }
    OmniSheet(tr(R.string.common_parameters), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MusicSliderRow(tr(R.string.music_tempo), "${settings.bpm} BPM", settings.bpm, 50..220) { viewModel.setMusicBpm(it) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.music_cot_label), color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                OmniTabs(
                    items = CoTMode.entries,
                    selected = settings.cotMode,
                    label = { cotLabels[it] ?: it.name },
                    onSelect = viewModel::setMusicCotMode
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.music_target_duration), color = OmniColors.Muted, style = MaterialTheme.typography.bodyMedium)
                OmniTabs(
                    items = musicDurationChoices,
                    selected = settings.targetDurationSec,
                    label = { localized.getString(R.string.common_seconds_value, it) },
                    onSelect = viewModel::setMusicTargetDuration
                )
            }
            MusicSliderRow(tr(R.string.music_ode_steps), "${settings.odeSteps}", settings.odeSteps, 4..32) { viewModel.setMusicOdeSteps(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniTextField(
                    value = seedText,
                    onValueChange = { value ->
                        seedText = value
                        value.toLongOrNull()?.let(viewModel::setMusicSeed)
                    },
                    label = tr(R.string.common_seed),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { viewModel.setMusicSeed(Random.nextLong(100_000, 100_000_000)) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, tr(R.string.common_seed), tint = OmniColors.Muted)
                }
            }
            OmniTextField(
                value = guidanceText,
                onValueChange = { value ->
                    guidanceText = value
                    value.toFloatOrNull()?.takeIf { it.isFinite() }?.let(viewModel::setMusicGuidanceScale)
                },
                label = tr(R.string.music_guidance),
                modifier = Modifier.fillMaxWidth()
            )
            SettingRow(
                title = tr(R.string.music_vulkan_gpu),
                detail = tr(R.string.music_vulkan_hint),
                onClick = { viewModel.setMusicUseVulkan(!settings.useVulkan) },
                trailing = { Switch(checked = settings.useVulkan, onCheckedChange = null) }
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
    val abcLabel = tr(R.string.music_abc_score)
    val abcCopied = tr(R.string.music_abc_copied)
    val keySig = abcHeaderValue(score, "K")?.let { tr(R.string.music_key_sig, it) }
    val timeSig = abcHeaderValue(score, "M")?.let { tr(R.string.music_time_sig, it) }
    val details = listOfNotNull(keySig, timeSig).joinToString(" · ")
    OmniSheet(tr(R.string.music_score), onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title.ifBlank { tr(R.string.music_untitled) }, color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details.isNotEmpty()) {
                    Text(details, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = { copyAbcScore(context, score, abcLabel, abcCopied) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr(R.string.common_copy))
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

@Composable
private fun musicTrackMetadata(track: MusicTrack): String = listOfNotNull(
    track.durationSec.takeIf { it.isFinite() && it > 0f }?.let { formatMediaDuration(it) },
    track.bpm.takeIf { it > 0 }?.let { "$it BPM" }
).joinToString(" · ").ifBlank { tr(R.string.common_unknown) }

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

private fun copyAbcScore(context: Context, score: String, clipLabel: String, copiedToast: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, score))
    }.onSuccess {
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
    }
}

private fun shareMusicTrack(context: Context, track: MusicTrack, shareTitle: String) {
    val file = File(track.audioPath)
    if (!file.isFile) return
    val mimeType = musicAudioMimeType(file.extension) ?: return
    runCatching {
        shareMediaFile(context, file, mimeType, shareTitle)
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
