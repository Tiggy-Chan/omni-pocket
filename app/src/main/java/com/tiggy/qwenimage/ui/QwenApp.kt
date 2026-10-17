package com.tiggy.qwenimage.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiggy.qwenimage.QwenViewModel
import com.tiggy.qwenimage.data.GenerationJob
import com.tiggy.qwenimage.data.GenerationMode
import com.tiggy.qwenimage.data.GenerationSettings
import com.tiggy.qwenimage.data.ModelStatus
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import com.tiggy.qwenimage.data.AppDomain
import kotlinx.coroutines.launch
import com.tiggy.qwenimage.data.WorkspaceTab
import java.io.File

private val Ink = Color(0xFFF8F6FF)
private val Muted = Color(0xFFB7B3C8)
private val Dim = Color(0xFF858197)
private val Violet = Color(0xFFC5AEFF)
private val VioletDeep = Color(0xFF7760D7)
private val Mint = Color(0xFF8CE7D3)
private val Coral = Color(0xFFFFA6B8)

private fun Double.format1(): String = "%.1f".format(this)

@Composable
fun QwenApp(viewModel: QwenViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showError by remember { mutableStateOf<String?>(null) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    if (state.error != null && showError == null) showError = state.error

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            OmniDrawerContent(
                currentDomain = state.domain,
                onSelectDomain = viewModel::setDomain,
                onSelectTab = { tab ->
                    viewModel.setDomain(AppDomain.ImageStudio)
                    viewModel.selectTab(tab)
                },
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF090A14))) {
            LiquidBackdrop()
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopBar(
                        domain = state.domain,
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                },
                bottomBar = {
                    if (state.domain == AppDomain.ImageStudio) {
                        LiquidNavigation(state.tab, viewModel::selectTab)
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (state.domain == AppDomain.MusicStudio) {
                        MusicStudioScreen(state = state, viewModel = viewModel)
                    } else {
                        when (state.tab) {
                            WorkspaceTab.Generate -> GenerateScreen(
                                viewModel = viewModel,
                                settings = state.settings,
                                busy = state.isBusy,
                                progress = state.progress,
                                status = state.status,
                                resultPath = state.resultPath,
                                liveLogs = state.liveLogs,
                                jobs = state.jobs,
                                officialTurboAvailable = state.modelStatus?.manifest?.features?.contains("official-turbo") == true,
                                modelReady = state.modelStatus?.ready == true,
                                onPreviewImage = { previewImagePath = it }
                            )
                            WorkspaceTab.Queue -> QueueScreen(
                                jobs = state.jobs,
                                cancel = viewModel::cancelJob,
                                onReuseSettings = viewModel::useSettings,
                                onPreviewImage = { previewImagePath = it }
                            )
                            WorkspaceTab.Models -> ModelsScreen(
                                viewModel = viewModel,
                                status = state.modelStatus,
                                repo = state.repo,
                                revision = state.revision,
                                busy = state.isBusy,
                                progress = state.progress,
                                message = state.status
                            )
                            WorkspaceTab.Settings -> SettingsScreen(
                                viewModel = viewModel,
                                threads = state.cpuThreads,
                                keepLoaded = state.keepModelsLoaded,
                                status = state.status
                            )
                        }
                    }
                }
            }
        }
    }

    showError?.let { message ->
        AlertDialog(
            onDismissRequest = { showError = null; viewModel.clearError() },
            title = { Text("需要处理") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { showError = null; viewModel.clearError() }) { Text("知道了") } }
        )
    }

    previewImagePath?.let { path ->
        val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
        if (bitmap != null) {
            Dialog(onDismissRequest = { previewImagePath = null }) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable { previewImagePath = null }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GlassCard(padding = 12.dp) {
                        Image(
                            bitmap.asImageBitmap(),
                            "作品大图",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(File(path).name, color = Muted, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { previewImagePath = null }) {
                                Text("关闭预览", color = Ink, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidBackdrop() {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF15142B), Color(0xFF080A14), Color(0xFF101022))))
            drawCircle(
                brush = Brush.radialGradient(listOf(Violet.copy(alpha = .33f), Violet.copy(alpha = .08f), Color.Transparent)),
                radius = size.minDimension * .54f,
                center = androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .06f)
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFF4D9AFF).copy(alpha = .18f), Color(0xFF4D9AFF).copy(alpha = .04f), Color.Transparent)),
                radius = size.minDimension * .48f,
                center = androidx.compose.ui.geometry.Offset(size.width * .95f, size.height * .42f)
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Mint.copy(alpha = .12f), Color.Transparent)),
                radius = size.minDimension * .42f,
                center = androidx.compose.ui.geometry.Offset(size.width * .30f, size.height * .96f)
            )
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000))))
        }
        Box(
            Modifier
                .size(170.dp)
                .align(Alignment.TopEnd)
                .padding(top = 68.dp, end = 18.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFB99AFF).copy(alpha = .20f), Color.Transparent)))
        )
        Box(
            Modifier
                .size(120.dp)
                .align(Alignment.BottomStart)
                .padding(bottom = 112.dp, start = 14.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Mint.copy(alpha = .16f), Color.Transparent)))
        )
    }
}

@Composable
private fun TopBar(
    domain: AppDomain,
    onOpenDrawer: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (domain == AppDomain.ImageStudio)
                        Brush.linearGradient(listOf(Color(0xFFAB8BFF), Color(0xFF6756C6)))
                    else
                        Brush.linearGradient(listOf(Color(0xFF5ED4BC), Color(0xFF388879)))
                )
                .border(1.dp, Color.White.copy(alpha = .48f), RoundedCornerShape(16.dp))
                .clickable { onOpenDrawer() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (domain == AppDomain.ImageStudio) Icons.Outlined.AutoAwesome else Icons.Outlined.MusicNote,
                "菜单",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .clickable { onOpenDrawer() }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Omni Pocket", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (domain == AppDomain.ImageStudio) Violet.copy(alpha = 0.2f)
                            else Mint.copy(alpha = 0.2f)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (domain == AppDomain.ImageStudio) "绘卷" else "乐坊",
                        color = if (domain == AppDomain.ImageStudio) Violet else Mint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                if (domain == AppDomain.ImageStudio) "Qwen Image 2.1 · 8步 Turbo" else "YuE2-3B · 48kHz 符号乐理",
                color = Muted,
                style = MaterialTheme.typography.labelMedium
            )
        }
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Icon(Icons.Outlined.Menu, "切换业务", tint = Ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LiquidNavigation(tab: WorkspaceTab, select: (WorkspaceTab) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.verticalGradient(listOf(Color(0x3AFFFFFF), Color(0x15FFFFFF))))
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(.38f), Color.White.copy(.08f))), RoundedCornerShape(30.dp))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            WorkspaceTab.entries.forEach { item ->
                val selected = item == tab
                Box(
                    Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) Brush.linearGradient(listOf(Color(0x77B49AFF), Color(0x3B6E5BC3))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                        .border(if (selected) 1.dp else 0.dp, if (selected) Color.White.copy(.24f) else Color.Transparent, RoundedCornerShape(22.dp))
                        .clickable { select(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(tabIcon(item), null, tint = if (selected) Ink else Muted, modifier = Modifier.size(21.dp))
                        Text(tabLabel(item), color = if (selected) Ink else Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerateScreen(
    viewModel: QwenViewModel,
    settings: GenerationSettings,
    busy: Boolean,
    progress: Int,
    status: String,
    resultPath: String?,
    liveLogs: List<String>,
    jobs: List<GenerationJob>,
    officialTurboAvailable: Boolean,
    modelReady: Boolean,
    onPreviewImage: (String) -> Unit
) {
    val inputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.setInput(uri) }
    val input2Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.setInput(uri, second = true) }
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(padding = 9.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(settings.mode == GenerationMode.TextToImage, "文生图", Icons.Outlined.AutoAwesome) { viewModel.setMode(GenerationMode.TextToImage) }
                ModeChip(settings.mode == GenerationMode.ImageEdit, "智能编辑", Icons.Outlined.Image) { viewModel.setMode(GenerationMode.ImageEdit) }
            }
        }

        GlassCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Violet.copy(.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Violet, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("描述你想看到的画面", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Prompt studio", color = Dim, style = MaterialTheme.typography.labelSmall)
                }
                Text(if (settings.officialTurbo) "官方 Turbo" else "Q4_K_M", color = Mint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            GlassTextField(
                value = settings.prompt,
                onValueChange = viewModel::setPrompt,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = "正向提示词",
                placeholder = "A cinematic portrait with precise typography…"
            )
            Spacer(Modifier.height(8.dp))
            GlassTextField(
                value = settings.negativePrompt,
                onValueChange = viewModel::setNegativePrompt,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = "负向提示词",
                placeholder = "deformed anatomy, blurry, watermark…"
            )
            Text("MNN prompt-only 路径会把负向内容合并为 Avoid 指令。", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PresetChip("人像", Icons.Outlined.Image, Modifier.weight(1f)) { viewModel.setPrompt("A refined adult portrait, soft cinematic light, natural face, detailed fabric, elegant composition") }
            PresetChip("透明", Icons.Outlined.AutoAwesome, Modifier.weight(1f)) { viewModel.setTransparent(true); viewModel.setPrompt("A cute illustrated character sticker, clean silhouette, vibrant pastel colors") }
            PresetChip("16:9", Icons.Outlined.Tune, Modifier.weight(1f)) { viewModel.setSize(512, 288) }
        }

        if (settings.mode == GenerationMode.ImageEdit) {
            GlassCard(accent = Mint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Mint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("参考图", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(if (settings.inputUri2 != null) "双图" else "单图", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { inputPicker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (settings.inputUri == null) "选择图片" else "更换图片")
                    }
                    IconButton(onClick = { input2Picker.launch(arrayOf("image/*")) }) { Icon(Icons.Outlined.AddPhotoAlternate, "第二张参考图", tint = Mint) }
                }
                Text(if (settings.inputUri == null) "未选择参考图" else if (settings.inputUri2 == null) "已选择 1 张参考图" else "已选择 2 张参考图", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }

        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip("画幅", "${settings.width}×${settings.height}", Modifier.weight(1f))
                MetricChip("步数", when { settings.officialTurbo -> "8"; settings.turbo -> "6"; else -> settings.steps.toString() }, Modifier.weight(1f))
                MetricChip("种子", settings.seed.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(320 to 320, 384 to 384, 512 to 512).forEach { (width, height) ->
                    SizeChip(settings.width == width && settings.height == height, "${width}²", Modifier.weight(1f)) { viewModel.setSize(width, height) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Checkbox(settings.officialTurbo, viewModel::setOfficialTurbo, enabled = officialTurboAvailable)
                Text(if (officialTurboAvailable) "官方 Turbo · 8 步" else "官方 Turbo 未安装", color = if (officialTurboAvailable) Ink else Muted)
                Spacer(Modifier.width(8.dp))
                Checkbox(settings.useTinyVae, viewModel::setTinyVae)
                Text("Tiny VAE", color = Ink)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "更多设置", tint = Muted) }
            }
            if (expanded) {
                GlassTextField(settings.steps.toString(), { it.toIntOrNull()?.let(viewModel::setSteps) }, Modifier.fillMaxWidth(), 1, "Steps", "20")
                Spacer(Modifier.height(6.dp))
                GlassTextField(settings.seed.toString(), { it.toIntOrNull()?.let(viewModel::setSeed) }, Modifier.fillMaxWidth(), 1, "Seed", "42")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(settings.transparent, viewModel::setTransparent); Text("透明 RGBA 输出", color = Ink) }
            }
        }

        if (busy) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().clip(CircleShape), color = Violet, trackColor = Color.White.copy(.12f))
        }
        Text(status, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(
            onClick = viewModel::enqueue,
            enabled = !busy && modelReady,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(21.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VioletDeep.copy(.90f), contentColor = Color.White, disabledContainerColor = Color.White.copy(.08f), disabledContentColor = Muted)
        ) {
            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (settings.mode == GenerationMode.TextToImage) "开始生成" else "开始编辑", fontWeight = FontWeight.SemiBold)
        }

        // 实时推理流程详细 Log 卡片
        if (liveLogs.isNotEmpty()) {
            GlassCard(padding = 12.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Terminal, "日志", tint = Violet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("实时推理流程日志", color = Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), color = Violet, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("推理中", color = Color(0xFFFFC36B), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("空闲", color = Mint, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF07080F).copy(alpha = 0.88f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                        .padding(10.dp)
                ) {
                    val logScroll = rememberScrollState()
                    LaunchedEffect(liveLogs.size) {
                        logScroll.animateScrollTo(logScroll.maxValue)
                    }
                    Column(Modifier.verticalScroll(logScroll), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        liveLogs.forEach { line ->
                            Text(
                                line,
                                color = when {
                                    line.contains("失败") || line.contains("错误") -> Coral
                                    line.contains("完成") || line.contains("就绪") -> Mint
                                    line.contains("采样") || line.contains("启动") -> Color(0xFFFFC36B)
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

        // 最新成图展示卡片
        resultPath?.let { path ->
            val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
            if (bitmap != null) {
                GlassCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最新作品", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("点击查看大图", color = Dim, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap.asImageBitmap(),
                        "生成结果",
                        Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onPreviewImage(path) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(File(path).name, color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // 历史作品画廊展示 (可横向滑动浏览之前所有生成出的图)
        val completedJobs = remember(jobs) {
            jobs.filter { it.state == "done" && it.outputPath != null && File(it.outputPath).exists() }.reversed()
        }
        if (completedJobs.isNotEmpty()) {
            GlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Collections, null, tint = Violet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("作品画廊 (${completedJobs.size})", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("横向滑动", color = Dim, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(completedJobs, key = { it.id }) { job ->
                        val path = job.outputPath!!
                        val bmp = remember(path) { BitmapFactory.decodeFile(path) }
                        if (bmp != null) {
                            Column(
                                Modifier
                                    .width(135.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                    .clickable { onPreviewImage(path) }
                                    .padding(6.dp)
                            ) {
                                Image(
                                    bmp.asImageBitmap(),
                                    null,
                                    modifier = Modifier
                                        .size(123.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    job.settings.prompt,
                                    color = Ink,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${job.settings.width}×${job.settings.height} · ${if (job.settings.officialTurbo) "Turbo" else "${job.settings.steps}步"}",
                                    color = Muted,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun QueueScreen(
    jobs: List<GenerationJob>,
    cancel: (String) -> Unit,
    onReuseSettings: (GenerationSettings) -> Unit,
    onPreviewImage: (String) -> Unit
) {
    if (jobs.isEmpty()) return EmptyState(Icons.Outlined.Queue, "队列还是空的", "提交一个 prompt，作品会在这里排队")
    // 逆序排列：最新提交/生成的任务排在最开头，旧任务往下沉
    val reversedJobs = remember(jobs) { jobs.asReversed() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(reversedJobs, key = { it.id }) { job ->
            val timeStr = remember(job.createdAt) {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(job.createdAt))
            }
            GlassCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        when (job.state) {
                            "done" -> Icons.Outlined.CheckCircle
                            "running" -> Icons.Outlined.AutoAwesome
                            else -> Icons.Outlined.Queue
                        },
                        null,
                        tint = when (job.state) {
                            "done" -> Mint
                            "running" -> Color(0xFFFFC36B)
                            "failed" -> Coral
                            else -> Violet
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(job.settings.prompt, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(timeStr, color = Dim, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${job.settings.mode} · ${job.settings.width}×${job.settings.height} · seed ${job.settings.seed}${if (job.settings.officialTurbo) " · 官方Turbo" else ""}",
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Text(
                                when (job.state) {
                                    "done" -> "生成完成"
                                    "running" -> "正在生成"
                                    "queued" -> "排队中"
                                    "cancelled" -> "已取消"
                                    else -> "生成失败"
                                },
                                color = when (job.state) {
                                    "done" -> Mint
                                    "running" -> Color(0xFFFFC36B)
                                    "failed" -> Coral
                                    else -> Muted
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (job.elapsedMs > 0) {
                                Text(" · ${(job.elapsedMs / 1000.0).format1()}s", color = Dim, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (job.state == "queued") {
                        IconButton(onClick = { cancel(job.id) }) { Icon(Icons.Outlined.Cancel, "取消", tint = Muted) }
                    }
                }

                // 历史成图预览：如果已完成且存在输出图片，直接内嵌展示并支持点击大图预览
                if (job.state == "done" && job.outputPath != null) {
                    val file = File(job.outputPath)
                    if (file.exists()) {
                        val bitmap = remember(job.outputPath) { BitmapFactory.decodeFile(job.outputPath) }
                        if (bitmap != null) {
                            Spacer(Modifier.height(10.dp))
                            Image(
                                bitmap.asImageBitmap(),
                                "作品预览",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onPreviewImage(job.outputPath) }
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(file.name, color = Dim, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                                TextButton(onClick = { onReuseSettings(job.settings) }) {
                                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(15.dp), tint = Violet)
                                    Spacer(Modifier.width(4.dp))
                                    Text("复用此参数", color = Violet, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                if (job.state == "running") {
                    LinearProgressIndicator(
                        progress = { job.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 9.dp).clip(CircleShape),
                        color = Violet,
                        trackColor = Color.White.copy(.12f)
                    )
                }
                job.error?.let { Text(it, color = Coral, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun ModelsScreen(viewModel: QwenViewModel, status: ModelStatus?, repo: String, revision: String, busy: Boolean, progress: Int, message: String) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) viewModel.importModels(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Memory, null, tint = Violet); Spacer(Modifier.width(8.dp)); Text("模型中心", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text(if (status?.manifest?.features?.contains("official-turbo") == true) "UC · Turbo" else "Q4_K_M", color = Mint, style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(8.dp)); Text("UC Q4_K_M 与官方 Turbo 共用文本编码器 / Tiny VAE；官方 Turbo 使用 8 步 DiT。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp)); Text(status?.message ?: "等待模型", color = if (status?.ready == true) Mint else Muted, style = MaterialTheme.typography.labelMedium)
            if (busy) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape), color = Violet, trackColor = Color.White.copy(.12f))
            Text(message, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(onClick = viewModel::checkRuntime, enabled = !busy && status?.ready == true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("检查推理引擎") }
        }
        GlassCard {
            Text("远程模型仓库", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp)); GlassTextField(repo, viewModel::setRepo, Modifier.fillMaxWidth(), 1, "Hugging Face owner/repo", "abenzerps/Qwen-Image-2.1-Uncensored-GGUF")
            Spacer(Modifier.height(6.dp)); GlassTextField(revision, viewModel::setRevision, Modifier.fillMaxWidth(), 1, "Revision", "main")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = viewModel::downloadModels, enabled = !busy && repo.isNotBlank(), modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("下载") }
                OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导入") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: QwenViewModel, threads: Int, keepLoaded: Boolean, status: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Settings, null, tint = Violet); Spacer(Modifier.width(8.dp)); Text("偏好与性能", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }; Spacer(Modifier.height(12.dp)); Text("CPU 线程：$threads", color = Ink); Slider(value = threads.toFloat(), onValueChange = { viewModel.setCpuThreads(it.toInt()) }, valueRange = 2f..8f, steps = 5); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keepLoaded, viewModel::setKeepModelsLoaded); Text("保持模型常驻", color = Ink) }; Text("默认关闭，降低 24GB 手机的内存峰值。", color = Muted, style = MaterialTheme.typography.labelSmall); Text("当前：$status", color = Muted, style = MaterialTheme.typography.labelSmall) }
        GlassCard { Text("运行配置", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text("DiT · Adreno OpenCL", color = Muted); Text("Qwen3-VL 文本/视觉 · CPU int4", color = Muted); Text("Tiny VAE · FP16 GPU", color = Muted); Text("模型许可：Qwen Research License", color = Muted, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, accent: Color = Violet, padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(27.dp)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(.42f), spotColor = accent.copy(.22f))
            .clip(shape)
            .drawLiquidGlass(accent)
            .padding(padding)
    ) { Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content) }
}

private fun Modifier.drawLiquidGlass(accent: Color): Modifier = this.drawWithCache {
    val fill = Brush.verticalGradient(listOf(Color.White.copy(.13f), Color.White.copy(.055f), Color(0x10000000)))
    val rim = Brush.linearGradient(listOf(Color.White.copy(.52f), Color.White.copy(.14f), accent.copy(.34f), Color.White.copy(.18f)))
    val shine = Brush.verticalGradient(listOf(Color.White.copy(.12f), Color.Transparent), startY = 0f, endY = 115.dp.toPx())
    val radius = 27.dp.toPx()
    onDrawWithContent {
        drawRoundRect(fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
        drawRoundRect(shine, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
        drawContent()
        drawRoundRect(rim, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius), style = Stroke(width = 1.2.dp.toPx()))
        drawRoundRect(accent.copy(.18f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius), style = Stroke(width = .55.dp.toPx()))
    }
}

@Composable
private fun GlassPill(selected: Boolean, accent: Color = Violet, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) Brush.linearGradient(listOf(accent.copy(.58f), accent.copy(.25f))) else Brush.linearGradient(listOf(Color.White.copy(.13f), Color.White.copy(.05f))))
            .border(1.dp, if (selected) Color.White.copy(.48f) else Color.White.copy(.22f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun GlassTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier, minLines: Int, label: String, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        minLines = minLines,
        maxLines = if (minLines == 1) 1 else 7,
        textStyle = TextStyle(color = Ink, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Dim) },
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            focusedContainerColor = Color.White.copy(.065f),
            unfocusedContainerColor = Color.White.copy(.035f),
            focusedBorderColor = Violet,
            unfocusedBorderColor = Color.White.copy(.36f),
            focusedLabelColor = Violet,
            unfocusedLabelColor = Muted,
            cursorColor = Violet
        )
    )
}

@Composable
private fun ModeChip(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
    GlassPill(selected, modifier = Modifier.clickable(onClick = onClick)) { Icon(icon, null, tint = if (selected) Ink else Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(label, color = if (selected) Ink else Muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun PresetChip(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassPill(false, modifier = modifier.clickable(onClick = onClick)) { Icon(icon, null, tint = Violet, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(label, color = Ink, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

@Composable
private fun SizeChip(selected: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassPill(selected, modifier = modifier.clickable(onClick = onClick), accent = Violet) { Text(label, color = if (selected) Ink else Muted, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun MetricChip(name: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(17.dp)).background(Color.White.copy(.055f)).border(1.dp, Color.White.copy(.08f), RoundedCornerShape(17.dp)).padding(horizontal = 11.dp, vertical = 9.dp)) {
        Text(name, color = Dim, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Ink, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Violet.copy(.16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Violet, modifier = Modifier.size(38.dp)) }
        Spacer(Modifier.height(14.dp)); Text(title, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun tabIcon(tab: WorkspaceTab): ImageVector = when (tab) {
    WorkspaceTab.Generate -> Icons.Outlined.AutoAwesome
    WorkspaceTab.Queue -> Icons.Outlined.Queue
    WorkspaceTab.Models -> Icons.Outlined.Memory
    WorkspaceTab.Settings -> Icons.Outlined.Settings
}

private fun tabLabel(tab: WorkspaceTab): String = when (tab) {
    WorkspaceTab.Generate -> "生成"
    WorkspaceTab.Queue -> "队列"
    WorkspaceTab.Models -> "模型"
    WorkspaceTab.Settings -> "设置"
}
