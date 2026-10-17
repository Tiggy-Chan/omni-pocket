package com.tiggy.qwenimage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.CoTMode
import com.tiggy.qwenimage.data.MusicPresets
import com.tiggy.qwenimage.data.MusicSettings
import com.tiggy.qwenimage.data.MusicStudioTab
import com.tiggy.qwenimage.data.MusicStylePreset
import com.tiggy.qwenimage.data.MusicTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private val Ink = Color(0xFFF8F6FF)
private val Muted = Color(0xFF9E9AA9)
private val Subtle = Color(0xFF6B6678)
private val CardBorder = Color(0x22FFFFFF)
private val CardBg = Color(0x33171729)
private val Purple = Color(0xFFC5AEFF)
private val Mint = Color(0xFF8CE7D3)
private val Rose = Color(0xFFFFAEC5)

@Composable
fun MusicStudioScreen(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Top Tab Segment
        MusicTabSegment(
            selectedTab = state.musicTab,
            onSelect = viewModel::selectMusicTab
        )

        Spacer(Modifier.height(12.dp))

        when (state.musicTab) {
            MusicStudioTab.Compose -> MusicComposeView(state = state, viewModel = viewModel)
            MusicStudioTab.Playlist -> MusicPlaylistView(state = state, viewModel = viewModel)
            MusicStudioTab.Score -> MusicScoreView(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun MusicTabSegment(
    selectedTab: MusicStudioTab,
    onSelect: (MusicStudioTab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MusicStudioTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (isSelected) Modifier.background(
                            Brush.linearGradient(listOf(Purple.copy(alpha = 0.35f), Mint.copy(alpha = 0.2f)))
                        ) else Modifier.background(Color.Transparent)
                    )
                    .border(
                        1.dp,
                        if (isSelected) Purple.copy(alpha = 0.6f) else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (tab) {
                        MusicStudioTab.Compose -> Icons.Outlined.MusicNote
                        MusicStudioTab.Playlist -> Icons.AutoMirrored.Outlined.QueueMusic
                        MusicStudioTab.Score -> Icons.Outlined.Piano
                    }
                    Icon(
                        icon,
                        null,
                        tint = if (isSelected) Ink else Muted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tab.label,
                        color = if (isSelected) Ink else Muted,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicComposeView(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    val scrollState = rememberScrollState()
    var showAdvanced by remember { mutableStateOf(false) }
    val musicSettings = state.musicSettings

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Preset Carousel
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "风格预设胶囊",
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MusicPresets.presets) { preset ->
                    val isSelected = musicSettings.style == preset.stylePrompt
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) Purple.copy(alpha = 0.25f) else CardBg)
                            .border(1.dp, if (isSelected) Purple else CardBorder, RoundedCornerShape(14.dp))
                            .clickable { viewModel.applyMusicPreset(preset) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column {
                            Text(preset.name, color = if (isSelected) Purple else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${preset.genre} · ${preset.bpm} BPM", color = Subtle, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // 2. Song Title & Style Input Card
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = Purple, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("曲目命名与风格描述", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedTextField(
                    value = musicSettings.title,
                    onValueChange = { viewModel.setMusicTitle(it) },
                    label = { Text("曲目标题 (Title)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedBorderColor = Purple,
                        unfocusedBorderColor = CardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = musicSettings.style,
                    onValueChange = { viewModel.setMusicStyle(it) },
                    label = { Text("音乐风格提示词 (Style Prompt)") },
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedBorderColor = Purple,
                        unfocusedBorderColor = CardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // BPM Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("节拍速度: ${musicSettings.bpm} BPM", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                }
                Slider(
                    value = musicSettings.bpm.toFloat(),
                    onValueChange = { viewModel.setMusicBpm(it.toInt()) },
                    valueRange = 60f..180f,
                    colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint.copy(alpha = 0.7f))
                )
            }
        }

        // 3. Structured Lyrics Editor
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Piano, null, tint = Mint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("结构化歌词 (Lyrics)", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    val charCount = musicSettings.lyrics.count { !it.isWhitespace() }
                    Text("$charCount 字 · ~${charCount / 3}秒演唱", color = Subtle, fontSize = 11.sp)
                }

                // Quick Insert Tag Chips
                Text("快速插入乐段标记：", color = Subtle, fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tags = listOf("[Intro]", "[Verse]", "[Pre-Chorus]", "[Chorus]", "[Bridge]", "[Outro]", "[Instrumental]")
                    items(tags) { tag ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    val newLyrics = if (musicSettings.lyrics.isBlank()) tag
                                    else "${musicSettings.lyrics.trimEnd()}\n\n$tag\n"
                                    viewModel.setMusicLyrics(newLyrics)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(tag, color = Purple, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                OutlinedTextField(
                    value = musicSettings.lyrics,
                    onValueChange = { viewModel.setMusicLyrics(it) },
                    placeholder = { Text("在此输入歌词，使用 [Verse]、[Chorus] 划分段落...") },
                    minLines = 6,
                    maxLines = 12,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedBorderColor = Mint,
                        unfocusedBorderColor = CardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 4. CoT Symbolic Planning Mode Selector
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = Rose, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("符号乐理规划模式 (CoT Mode)", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                CoTMode.entries.forEach { mode ->
                    val isSelected = musicSettings.cotMode == mode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Rose.copy(alpha = 0.18f) else Color.Transparent)
                            .border(1.dp, if (isSelected) Rose.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { viewModel.setMusicCotMode(mode) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (isSelected) Rose else Subtle, CircleShape)
                                .padding(3.dp)
                        ) {
                            if (isSelected) {
                                Box(Modifier.fillMaxSize().clip(CircleShape).background(Rose))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(mode.label, color = if (isSelected) Ink else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(mode.description, color = Subtle, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 5. Advanced Settings Expander
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                .clickable { showAdvanced = !showAdvanced }
                .padding(14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = Purple, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("高级生成参数微调", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Icon(if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Muted)
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column(
                        Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Duration selection
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Timer, null, tint = Mint, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("预估歌曲时长: ~${musicSettings.targetDurationSec} 秒", color = Muted, fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30 to "短曲 30s", 60 to "片段 60s", 90 to "标准单曲 90s", 150 to "完整长曲 150s").forEach { (sec, label) ->
                                val sel = musicSettings.targetDurationSec == sec
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) Mint.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                        .border(1.dp, if (sel) Mint else CardBorder, RoundedCornerShape(10.dp))
                                        .clickable { viewModel.setMusicTargetDuration(sec) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (sel) Ink else Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        // Seed Input
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = musicSettings.seed.toString(),
                                onValueChange = { v -> v.toLongOrNull()?.let { viewModel.setMusicSeed(it) } },
                                label = { Text("随机数种子 (Seed)") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Ink,
                                    unfocusedTextColor = Ink,
                                    focusedBorderColor = Purple,
                                    unfocusedBorderColor = CardBorder
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { viewModel.setMusicSeed(Random.nextLong(100000, 99999999)) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Purple.copy(alpha = 0.15f))
                            ) {
                                Icon(Icons.Outlined.Refresh, null, tint = Purple)
                            }
                        }

                        // Flow Matching ODE Steps
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Flow Matching ODE 步数: ${musicSettings.odeSteps} 步", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(16 to "16 步极速", 32 to "32 步全精度").forEach { (step, name) ->
                                    val sel = musicSettings.odeSteps == step
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) Rose.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                            .border(1.dp, if (sel) Rose else CardBorder, RoundedCornerShape(8.dp))
                                            .clickable { viewModel.setMusicOdeSteps(step) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(name, color = if (sel) Ink else Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Start Generation Big Action Button
        Button(
            onClick = { viewModel.startMusicGeneration() },
            enabled = !state.isBusy,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.White.copy(alpha = 0.05f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    brush = if (!state.isBusy) Brush.linearGradient(listOf(Color(0xFF9B7BFF), Color(0xFF5ED4BC)))
                    else Brush.linearGradient(listOf(Color(0xFF333344), Color(0xFF222233))),
                    shape = RoundedCornerShape(18.dp)
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isBusy) {
                    CircularProgressIndicator(color = Ink, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在生成音乐...", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Outlined.MusicNote, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("开始端侧编曲与演唱", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 7. Live Generation Status & Engineering Logs Card
        if (state.liveLogs.isNotEmpty() || state.isBusy) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0C0D1A).copy(alpha = 0.92f))
                    .border(1.dp, Purple.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, "日志", tint = Purple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("实时音乐推理流程日志", color = Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        if (state.isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), color = Mint, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("推理中 ${state.progress}%", color = Color(0xFFFFC36B), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("最新生成已就绪", color = Mint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (state.isBusy) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = Mint,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF06070E))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        val logScroll = rememberScrollState()
                        androidx.compose.runtime.LaunchedEffect(state.liveLogs.size) {
                            logScroll.animateScrollTo(logScroll.maxValue)
                        }
                        Column(
                            Modifier.verticalScroll(logScroll),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            state.liveLogs.forEach { line ->
                                Text(
                                    line,
                                    color = when {
                                        line.contains("失败") || line.contains("异常") || line.contains("Error") -> Rose
                                        line.contains("完成") || line.contains("Finished") || line.contains("已存入") -> Mint
                                        line.contains("Starting") || line.contains("Running") || line.contains("阶段") || line.contains("任务启动") -> Color(0xFFFFC36B)
                                        line.contains("规格") || line.contains("计算调度") || line.contains("调式") -> Purple
                                        else -> Color(0xFFC5C7DB)
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 8. Latest Generated Song Preview Card (Immediate Playback & Inspection)
        state.activeMusicTrack?.let { activeTrack ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF221A3D), Color(0xFF13222B))
                        )
                    )
                    .border(1.dp, Mint.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (state.isMusicPlaying) Mint else Purple.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { viewModel.toggleMusicPlayPause() }) {
                                Icon(
                                    if (state.isMusicPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    null,
                                    tint = if (state.isMusicPlaying) Color.Black else Ink,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "最新成果 · ${activeTrack.title}",
                                color = Ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${activeTrack.bpm} BPM · ${"%.0f".format(activeTrack.durationSec)}s · 48kHz 双声道",
                                color = Mint,
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = { viewModel.selectMusicTab(MusicStudioTab.Playlist) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("进曲库", color = Ink, fontSize = 11.sp)
                        }
                    }

                    AnimatedWaveform(isPlaying = state.isMusicPlaying)
                }
            }
        }
    }
}

@Composable
private fun MusicPlaylistView(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    val context = LocalContext.current
    val activeTrack = state.activeMusicTrack
    val isPlaying = state.isMusicPlaying
    val posMs = state.musicPositionMs
    val durMs = state.musicDurationMs

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Player Card
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF241C42), Color(0xFF131F33))
                    )
                )
                .border(1.dp, Purple.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.radialGradient(listOf(Purple, Color(0xFF5640A0))))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.MusicNote, null, tint = Ink, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            activeTrack?.title ?: "未选择播放曲目",
                            color = Ink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            activeTrack?.style ?: "点击下方曲库卡片开始试听",
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Waveform Animation
                AnimatedWaveform(isPlaying = isPlaying)

                // Seekbar & Time
                Column {
                    Slider(
                        value = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { fraction ->
                            if (durMs > 0) viewModel.seekMusic((fraction * durMs).toInt())
                        },
                        colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint),
                        modifier = Modifier.fillMaxWidth().height(24.dp)
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatDuration(posMs), color = Subtle, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(durMs), color = Subtle, fontSize = 11.sp)
                    }
                }

                // Controls
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.toggleMusicPlayPause() },
                        enabled = activeTrack != null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Purple, Mint)))
                    ) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            null,
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.width(24.dp))

                    if (activeTrack != null) {
                        IconButton(
                            onClick = { shareAudioFile(context, activeTrack) },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Icon(Icons.Outlined.Share, null, tint = Ink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // Playlist Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已生成歌曲库 (${state.musicPlaylist.size})", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("48kHz 双声道高保真", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }

        // Playlist Items
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.musicPlaylist) { track ->
                val isCurrent = activeTrack?.id == track.id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isCurrent) Purple.copy(alpha = 0.15f) else CardBg)
                        .border(1.dp, if (isCurrent) Purple.copy(alpha = 0.6f) else CardBorder, RoundedCornerShape(16.dp))
                        .clickable { viewModel.playMusic(track) }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCurrent && isPlaying) Mint else Purple.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isCurrent && isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                null,
                                tint = if (isCurrent && isPlaying) Color.Black else Ink,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${track.bpm} BPM · ${"%.0f".format(track.durationSec)}秒 · CoT:${track.cotMode.tag}", color = Subtle, fontSize = 11.sp)
                        }

                        if (!track.abcScore.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    viewModel.setActiveTrack(track)
                                    viewModel.selectMusicTab(MusicStudioTab.Score)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Outlined.Piano, "查看简谱", tint = Mint, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(
                            onClick = { shareAudioFile(context, track) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Outlined.Share, "分享", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (state.liveLogs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0C0D1A).copy(alpha = 0.92f))
                            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Terminal, "日志", tint = Purple, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("历史生成流程日志", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text("${state.liveLogs.size} 条记录", color = Subtle, fontSize = 11.sp)
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF06070E))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                    .padding(10.dp)
                            ) {
                                val logScroll = rememberScrollState()
                                Column(Modifier.verticalScroll(logScroll), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    state.liveLogs.forEach { line ->
                                        Text(
                                            line,
                                            color = when {
                                                line.contains("失败") || line.contains("异常") -> Rose
                                                line.contains("完成") || line.contains("Finished") -> Mint
                                                line.contains("Starting") || line.contains("Running") || line.contains("任务启动") -> Color(0xFFFFC36B)
                                                line.contains("规格") || line.contains("计算调度") -> Purple
                                                else -> Color(0xFFC5C7DB)
                                            },
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicScoreView(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    val context = LocalContext.current
    val track = state.activeMusicTrack ?: state.musicPlaylist.firstOrNull()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Explanation Card
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E1735), Color(0xFF142426))))
                .border(1.dp, Mint.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Piano, null, tint = Mint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("YuE2 独创符号乐理规划 (Symbolic ABC)", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "不同于将人声与背景乐分离的传统方案，YuE2 在自回归阶段首先推演出正规 ABC 乐理代码，精确固化调号（Key）、拍号（Meter）、速度（Tempo）与人声旋律线（Vocal Melody），确保每一个歌词汉字/假名与音符严丝合缝，再驱动连续声学流匹配生成音频。",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        if (track?.abcScore != null) {
            // Score Display Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0D0E1A))
                    .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("调式: G#m · 速度: ${track.bpm} BPM · 4/4 拍", color = Mint, fontSize = 11.sp)
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("ABC Score", track.abcScore))
                                Toast.makeText(context, "ABC 乐谱已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null, tint = Purple, modifier = Modifier.size(18.dp))
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(12.dp)
                    ) {
                        Text(
                            track.abcScore,
                            color = Color(0xFFD6C8FF),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBg)
                    .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.MusicNote, null, tint = Subtle, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("当前曲目使用 cot='off' 生成，无符号乐谱记录", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun AnimatedWaveform(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val anim1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "bar1"
    )
    val anim2 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "bar2"
    )
    val anim3 by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(350), RepeatMode.Reverse),
        label = "bar3"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bars = 32
        for (i in 0 until bars) {
            val scale = if (isPlaying) {
                when (i % 3) {
                    0 -> anim1 * ((i % 5 + 3) / 8f)
                    1 -> anim2 * ((i % 4 + 4) / 8f)
                    else -> anim3 * ((i % 6 + 2) / 8f)
                }
            } else {
                0.15f
            }
            Box(
                Modifier
                    .width(4.dp)
                    .height((28 * scale).coerceAtLeast(3f).dp)
                    .clip(CircleShape)
                    .background(
                        if (isPlaying) {
                            if (i % 2 == 0) Purple else Mint
                        } else Subtle.copy(alpha = 0.4f)
                    )
            )
        }
    }
}

private fun formatDuration(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

private fun shareAudioFile(context: Context, track: MusicTrack) {
    val file = File(track.audioPath)
    if (!file.exists()) {
        Toast.makeText(context, "音频文件未找到", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/*"
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        putExtra(Intent.EXTRA_SUBJECT, track.title)
        putExtra(Intent.EXTRA_TEXT, "${track.title} - 由 Omni Pocket 端侧生成\n${track.style}")
    }
    context.startActivity(Intent.createChooser(intent, "分享音乐曲目"))
}
