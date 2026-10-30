package com.tiggy.qwenimage.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.tiggy.qwenimage.data.TaskUiState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Maximum width of reading content (transcripts, forms) on wide screens. */
val ContentMaxWidth: Dp = 760.dp

/**
 * Standard workspace layout: a flat top bar, scrolling content and an optional floating composer.
 * [content] receives padding that clears both bars; the composer floats over content with glass.
 */
@Composable
fun WorkspaceScaffold(
    title: String,
    subtitle: String? = null,
    onMenu: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    composer: (@Composable (HazeState) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val haze = remember { HazeState() }
    val density = LocalDensity.current
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(OmniColors.Background)) {
        Box(Modifier.fillMaxSize().haze(haze)) {
            // Wait for both bars to be measured so lists that scroll to their end on first
            // composition see the final padding instead of zero.
            if (topHeight > 0 && (composer == null || bottomHeight > 0)) {
                content(
                    PaddingValues(
                        top = with(density) { topHeight.toDp() },
                        bottom = with(density) { bottomHeight.toDp() } + if (composer == null) 24.dp else 8.dp
                    )
                )
            }
        }
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).onSizeChanged { topHeight = it.height }) {
            WorkspaceTopBar(title, subtitle, onMenu, actions)
        }
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .onSizeChanged { bottomHeight = it.height }
                .imePadding().navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            if (composer != null) {
                Box(Modifier.widthIn(max = ContentMaxWidth + 32.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    composer(haze)
                }
            }
        }
    }
}

@Composable
fun WorkspaceTopBar(
    title: String,
    subtitle: String? = null,
    onMenu: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().background(OmniColors.Background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onMenu != null) {
                IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Menu, "打开侧栏", tint = OmniColors.Muted)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(thickness = 0.5.dp, color = OmniColors.Line)
    }
}

/** Centers a column of reading width inside a wide pane. */
@Composable
fun ReadingColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), content = content)
    }
}

/**
 * Glass composer: a multi-line field above a tool row. [tools] sits on the left of the tool row;
 * the round button on the right sends, or stops while [running].
 */
@Composable
fun OmniComposer(
    hazeState: HazeState,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = value.isNotBlank(),
    running: Boolean = false,
    onStop: (() -> Unit)? = null,
    enabled: Boolean = true,
    maxLines: Int = 6,
    status: (@Composable () -> Unit)? = null,
    tools: @Composable RowScope.() -> Unit = {}
) {
    OverlayGlass(hazeState, modifier.fillMaxWidth(), radius = 24.dp) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
            status?.let { Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp)) { it() } }
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                placeholder = { Text(placeholder, color = OmniColors.Dim) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = OmniColors.Ink),
                maxLines = maxLines,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = OmniColors.Accent
                )
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = tools)
                SendButton(
                    running = running,
                    enabled = if (running) onStop != null else sendEnabled && enabled,
                    onClick = { if (running) onStop?.invoke() else onSend() }
                )
            }
        }
    }
}

@Composable
fun SendButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val active = enabled || running
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(end = 4.dp).size(40.dp),
        shape = CircleShape,
        color = if (active) OmniColors.Accent else OmniColors.Selected
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (running) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                if (running) "停止" else "发送",
                tint = if (active) OmniColors.OnAccent else OmniColors.Dim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Small text button for composer tool rows, e.g. a mode or model picker. */
@Composable
fun ComposerChip(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentDescription: String? = null
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) OmniColors.AccentSoft else Color.Transparent,
        modifier = Modifier.heightIn(min = 40.dp)
    ) {
        Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription ?: label, tint = if (selected) OmniColors.Accent else OmniColors.Muted,
                    modifier = Modifier.size(18.dp))
                if (label.isNotEmpty()) Spacer(Modifier.width(4.dp))
            }
            if (label.isNotEmpty()) {
                Text(label, color = if (selected) OmniColors.Accent else OmniColors.Muted,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** Bottom sheet with the app palette and a plain title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OmniColors.Surface,
        contentColor = OmniColors.Ink,
        scrimColor = LocalOmniPalette.current.scrim
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = OmniColors.Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Uppercase-free section label for grouped settings and lists. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp))
}

/** One row in a settings-style list: title, optional detail, trailing control. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) {
                Text(detail, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** A request the user sent, shown right-aligned like a chat message. */
@Composable
fun RequestBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = OmniColors.Selected, shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)) {
            Text(text, color = OmniColors.Ink, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/** Slim progress line used under a running result: dot, stage text, optional step count, stop. */
@Composable
fun TaskLine(task: TaskUiState, onCancel: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (task.running) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(OmniColors.Accent))
                Spacer(Modifier.width(8.dp))
            }
            Text(task.status, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (task.running && task.progress != null) {
                Text("${task.progress}%", color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
            }
            if (task.running && onCancel != null) {
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Stop, "停止", tint = OmniColors.Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (task.running) {
            if (task.progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = OmniColors.Accent, trackColor = OmniColors.Line)
            } else {
                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = OmniColors.Accent, trackColor = OmniColors.Line, drawStopIndicator = {})
            }
        }
        task.error?.let {
            Text(it, color = OmniColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun ContentCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = OmniColors.Surface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, OmniColors.Line),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, color = OmniColors.Ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun OmniTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    minLines: Int = 1,
    maxLines: Int = if (minLines == 1) 1 else 8,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = OmniColors.Dim) } },
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = OmniColors.Ink),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OmniColors.Ink,
            unfocusedTextColor = OmniColors.Ink,
            focusedContainerColor = OmniColors.Field,
            unfocusedContainerColor = OmniColors.Field,
            disabledContainerColor = OmniColors.Field,
            focusedBorderColor = OmniColors.Accent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedLabelColor = OmniColors.Accent,
            unfocusedLabelColor = OmniColors.Muted,
            cursorColor = OmniColors.Accent
        )
    )
}

/** Segmented single choice. [accent] is kept for older call sites and ignored. */
@Composable
fun <T> OmniTabs(
    items: List<T>,
    selected: T,
    @Suppress("UNUSED_PARAMETER") accent: Color = Color.Unspecified,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OmniColors.Field).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) OmniColors.Raised else Color.Transparent)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.Tab, onClick = { onSelect(item) })
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(item),
                    color = if (isSelected) OmniColors.Ink else OmniColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun OmniPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    @Suppress("UNUSED_PARAMETER") accent: Color = Color.Unspecified
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OmniColors.Accent,
            contentColor = OmniColors.OnAccent,
            disabledContainerColor = OmniColors.Selected,
            disabledContentColor = OmniColors.Dim
        )
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = OmniColors.OnAccent, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun OmniTaskStatus(task: TaskUiState, onCancel: (() -> Unit)? = null) = TaskLine(task, onCancel)

@Composable
fun OmniLogDisclosure(logs: List<String>) {
    if (logs.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起日志" else "运行日志", color = OmniColors.Dim, style = MaterialTheme.typography.labelMedium)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = OmniColors.Dim,
                modifier = Modifier.size(16.dp))
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(OmniColors.Field).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                logs.takeLast(30).forEach { line ->
                    Text(line, color = OmniColors.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

@Composable
fun OmniAsyncImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxDimension: Int = 1024,
    contentScale: ContentScale = ContentScale.Fit
) {
    val bitmap = rememberImageBitmap(path, maxDimension)
    if (bitmap == null) {
        Box(modifier.background(OmniColors.Field), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Image, contentDescription, tint = OmniColors.Dim, modifier = Modifier.size(28.dp))
        }
    } else {
        Image(bitmap.asImageBitmap(), contentDescription, modifier = modifier, contentScale = contentScale)
    }
}

@Composable
fun rememberImageBitmap(path: String?, maxDimension: Int = 1024): Bitmap? {
    val result = produceState<Bitmap?>(initialValue = null, path, maxDimension) {
        value = path?.let { withContext(Dispatchers.IO) { decodeSampledBitmap(it, maxDimension) } }
    }
    DisposableEffect(path) {
        onDispose { result.value?.recycle() }
    }
    return result.value
}

@Composable
fun rememberUriBitmap(uri: Uri?, maxDimension: Int = 1024): Bitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val result = produceState<Bitmap?>(initialValue = null, uri, maxDimension) {
        value = uri?.let { withContext(Dispatchers.IO) { decodeSampledBitmap(context, it, maxDimension) } }
    }
    DisposableEffect(uri) {
        onDispose { result.value?.recycle() }
    }
    return result.value
}

@Composable
fun OmniEmptyState(icon: ImageVector, title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = OmniColors.Dim, modifier = Modifier.size(32.dp))
        Text(title, color = OmniColors.Ink, style = MaterialTheme.typography.titleSmall)
        if (subtitle.isNotEmpty()) Text(subtitle, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

fun shareMediaFile(context: Context, file: File, mimeType: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

fun formatMediaDuration(seconds: Float): String {
    if (!seconds.isFinite() || seconds <= 0) return "--:--"
    val total = seconds.toInt()
    return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
}

private fun decodeSampledBitmap(path: String, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val request = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
    return BitmapFactory.decodeFile(path, request)
}

private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val request = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension) }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, request) }
}

private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var size = 1
    while (width / (size * 2) >= maxDimension && height / (size * 2) >= maxDimension) size *= 2
    return size
}
