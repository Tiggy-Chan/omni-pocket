package com.tiggy.qwenimage.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MovieCreation
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.tiggy.qwenimage.QwenUiState
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.VideoItem
import com.tiggy.qwenimage.data.VideoStudioTab
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
private val Amber = Color(0xFFF59E0B)
private val Sunset = Color(0xFFFB923C)
private val Mint = Color(0xFF8CE7D3)
private val Rose = Color(0xFFFFAEC5)

@Composable
fun VideoStudioScreen(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Top Tab Segment
        VideoTabSegment(
            selectedTab = state.videoTab,
            onSelect = viewModel::selectVideoTab
        )

        Spacer(Modifier.height(12.dp))

        when (state.videoTab) {
            VideoStudioTab.Compose -> VideoComposeView(state = state, viewModel = viewModel)
            VideoStudioTab.Gallery -> VideoGalleryView(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun VideoTabSegment(
    selectedTab: VideoStudioTab,
    onSelect: (VideoStudioTab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        VideoStudioTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Amber.copy(alpha = 0.22f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (isSelected) Amber.copy(alpha = 0.6f) else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        when (tab) {
                            VideoStudioTab.Compose -> Icons.Outlined.MovieCreation
                            VideoStudioTab.Gallery -> Icons.Outlined.VideoLibrary
                        },
                        null,
                        tint = if (isSelected) Amber else Muted,
                        modifier = Modifier.size(16.dp)
                    )
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
private fun VideoComposeView(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    val scrollState = rememberScrollState()
    val videoSettings = state.videoSettings
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. MobileWan Architecture Banner
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Amber.copy(alpha = 0.15f), Sunset.copy(alpha = 0.08f))
                    )
                )
                .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Movie, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("MobileWan 移动端视频扩散引擎", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Amber.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("3步 DMD 极速", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    "高通 AI 研究院基于 Wan2.2-5B 剪枝 23% 注意力头并引入循环混合注意力 (RHA) 的移动端特化模型，仅需 3 步解算 81 帧 480×832 原生视频。",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("• 480×832 竖屏画质", color = Amber, fontSize = 11.sp)
                    Text("• 81 帧 (5.06s)", color = Amber, fontSize = 11.sp)
                    Text("• 16 FPS 电影帧率", color = Amber, fontSize = 11.sp)
                }
            }
        }

        // 2. Prompt Input Card
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
                    Icon(Icons.Outlined.MovieCreation, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("视频场景与运镜提示词 (Prompt)", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedTextField(
                    value = videoSettings.prompt,
                    onValueChange = { viewModel.setVideoPrompt(it) },
                    label = { Text("视频动态提示词") },
                    placeholder = {
                        Text("输入镜头运动、主体动态与光影，例如: A cinematic shot of a golden retriever running through a sunny backyard, weaving between flower beds...")
                    },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedBorderColor = Amber,
                        unfocusedBorderColor = CardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 3. Advanced Parameters Toggle
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .clickable { showAdvanced = !showAdvanced }
                .padding(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = Amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("高级推理参数配置 (Advanced)", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Icon(
                    if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = Muted
                )
            }
        }

        AnimatedVisibility(visible = showAdvanced) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBg)
                    .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Random Seed
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("随机种子 (Seed)", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(videoSettings.seed.toString(), color = Subtle, fontSize = 11.sp)
                        }
                        IconButton(onClick = { viewModel.setVideoSeed(Random.nextLong(1, 99999999L)) }) {
                            Icon(Icons.Outlined.Refresh, "随机种子", tint = Amber)
                        }
                    }

                    // Steps Selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DMD 扩散步数: ${videoSettings.steps} 步", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2 to "2 步极速", 3 to "3 步推荐", 4 to "4 步精细").forEach { (step, name) ->
                                val sel = videoSettings.steps == step
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) Amber.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                                        .border(1.dp, if (sel) Amber else CardBorder, RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setVideoSteps(step) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(name, color = if (sel) Ink else Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // Workstation Server URL
                    OutlinedTextField(
                        value = videoSettings.serverUrl,
                        onValueChange = { viewModel.setVideoServerUrl(it) },
                        label = { Text("MobileWan 工作站地址 (Workstation Server URL)") },
                        placeholder = { Text("http://127.0.0.1:8998 或 http://电脑局域网IP:8998") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Ink,
                            unfocusedTextColor = Ink,
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = CardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "提示：MobileWan 视频扩散大模型通过工作站桥接服务推演。若手机连电脑 USB，可在电脑终端运行 'adb reverse tcp:8998 tcp:8998' 后使用默认地址。",
                        color = Subtle,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 4. CTA Action Button
        Button(
            onClick = { viewModel.startVideoGeneration() },
            enabled = !state.isBusy && videoSettings.prompt.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Amber,
                disabledContainerColor = Color.White.copy(alpha = 0.08f)
            )
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    color = Color.Black,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text("正在求解视频扩散方程...", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("开始渲染视频 (MobileWan 3-Step)", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 5. Status & Progress Feedback
        if (state.isBusy) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(state.status, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("${state.progress}%", color = Muted, fontSize = 12.sp)
                }
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = Amber,
                    trackColor = Color.White.copy(alpha = 0.08f)
                )
            }
        }

        // Error Banner
        state.error?.let { err ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Rose.copy(alpha = 0.15f))
                    .border(1.dp, Rose.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = Rose, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(err, color = Rose, fontSize = 12.sp)
                }
            }
        }

        // 6. Terminal Console (Engineering Live Logs)
        VideoTerminalConsoleCard(logs = state.liveLogs)
    }
}

@Composable
private fun VideoTerminalConsoleCard(logs: List<String>) {
    var expanded by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0D0E18))
            .border(1.dp, Color(0xFF27293D), RoundedCornerShape(18.dp))
    ) {
        Column {
            // Terminal Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Amber)
                    )
                    Icon(Icons.Outlined.Terminal, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Text(
                        "计算调度与推理控制台 (Terminal Live Log)",
                        color = Ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = Muted,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 220.dp)
                        .background(Color(0xFF07080F))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            "> MobileWan 调度引擎已就绪...\n> 循环混合注意力 (Recurrent Hybrid Attention) 算子载入完毕\n> 等待视频渲染任务提交...",
                            color = Color(0xFF5B6078),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            logs.forEach { line ->
                                val color = when {
                                    line.contains("异常") || line.contains("失败") || line.contains("错误") -> Rose
                                    line.contains("完成") || line.contains("Finished") || line.contains("Complete") -> Mint
                                    line.contains("MobileWan") || line.contains("DMD") -> Amber
                                    else -> Color(0xFF9EAAAF)
                                }
                                Text(
                                    line,
                                    color = color,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
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

@Composable
private fun VideoGalleryView(
    state: QwenUiState,
    viewModel: QwenViewModel
) {
    val context = LocalContext.current
    val activeVideo = state.activeVideo

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Video Player Card
        if (activeVideo != null && File(activeVideo.videoPath).exists()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBg)
                    .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Video View
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(480f / 832f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(Uri.fromFile(File(activeVideo.videoPath)))
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                }
                            },
                            update = { view ->
                                view.setVideoURI(Uri.fromFile(File(activeVideo.videoPath)))
                                view.start()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Metadata
                    Text(
                        activeVideo.prompt,
                        color = Ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${activeVideo.resolution} · ${activeVideo.durationSec}s · ${activeVideo.fps} FPS · ${activeVideo.fileSizeBytes / 1024} KB",
                            color = Amber,
                            fontSize = 11.sp
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { shareVideo(context, activeVideo.videoPath) }) {
                                Icon(Icons.Outlined.Share, "分享视频", tint = Amber)
                            }
                            IconButton(onClick = { viewModel.deleteVideo(activeVideo) }) {
                                Icon(Icons.Outlined.Delete, "删除", tint = Rose)
                            }
                        }
                    }
                }
            }
        } else {
            // Empty State
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBg)
                    .border(1.dp, CardBorder, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.MovieCreation, null, tint = Subtle, modifier = Modifier.size(36.dp))
                    Text("暂无渲染视频，请在「创意渲染」中生成", color = Muted, fontSize = 13.sp)
                }
            }
        }

        // 2. Video Gallery List
        Text(
            "渲染成品列表 (${state.videoGallery.size})",
            color = Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        LazyColumn(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.videoGallery) { item ->
                val isSelected = item.id == activeVideo?.id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) Amber.copy(alpha = 0.15f) else CardBg)
                        .border(
                            1.dp,
                            if (isSelected) Amber.copy(alpha = 0.5f) else CardBorder,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { viewModel.setActiveVideo(item) }
                        .padding(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Amber.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, tint = Amber, modifier = Modifier.size(24.dp))
                        }

                        Column(Modifier.weight(1f)) {
                            Text(
                                item.prompt,
                                color = Ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${item.resolution} · ${item.durationSec}s · ${SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))}",
                                color = Subtle,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun shareVideo(context: Context, filePath: String) {
    runCatching {
        val file = File(filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享渲染视频"))
    }
}
