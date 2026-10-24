package com.tiggy.qwenimage.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.tiggy.qwenimage.data.TaskUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun ContentCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = OmniColors.Content,
        border = androidx.compose.foundation.BorderStroke(1.dp, OmniColors.Border),
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
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = OmniColors.Ink),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OmniColors.Ink,
            unfocusedTextColor = OmniColors.Ink,
            focusedContainerColor = Color(0xFF10141C),
            unfocusedContainerColor = Color(0xFF10141C),
            focusedBorderColor = OmniColors.Violet,
            unfocusedBorderColor = Color(0x40FFFFFF),
            focusedLabelColor = OmniColors.Violet,
            unfocusedLabelColor = OmniColors.Muted,
            cursorColor = OmniColors.Violet
        )
    )
}

@Composable
fun <T> OmniTabs(
    items: List<T>,
    selected: T,
    accent: Color,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 500f), label = "navPress")
            val isSelected = item == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (isSelected) Modifier.background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.16f))
                            )
                        ) else Modifier.background(Color(0xFF12161D))
                    )
                    .border(1.dp, if (isSelected) accent.copy(alpha = 0.48f) else OmniColors.Border, RoundedCornerShape(16.dp))
                    .selectable(
                        selected = isSelected,
                        interactionSource = interactionSource,
                        indication = androidx.compose.material3.ripple(),
                        role = Role.Tab,
                        onClick = { onSelect(item) }
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(item),
                    color = if (isSelected) OmniColors.Ink else OmniColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
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
    accent: Color = OmniColors.VioletDeep
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF242730),
            disabledContentColor = OmniColors.Muted
        )
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun OmniTaskStatus(task: TaskUiState, onCancel: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (task.running) {
            if (task.progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OmniColors.Mint, trackColor = Color(0xFF252932))
            } else {
                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = OmniColors.Mint, trackColor = Color(0xFF252932))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.status, color = OmniColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (task.running && onCancel != null) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
            if (task.progress != null && task.running) Text("${task.progress}%", color = OmniColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        task.error?.let { Text(it, color = OmniColors.Rose, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun OmniLogDisclosure(logs: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "隐藏运行日志" else "运行日志 (${logs.size})", color = OmniColors.Muted)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = OmniColors.Muted)
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF090B10)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                logs.takeLast(30).forEach { line ->
                    Text(line, color = OmniColors.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

@Composable
fun OmniAsyncImage(path: String, contentDescription: String?, modifier: Modifier = Modifier, maxDimension: Int = 1024) {
    val bitmap = rememberImageBitmap(path, maxDimension)
    if (bitmap == null) {
        Box(modifier.background(Color(0xFF10141B)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Image, contentDescription, tint = OmniColors.Dim, modifier = Modifier.size(28.dp))
        }
    } else {
        Image(bitmap.asImageBitmap(), contentDescription, modifier = modifier, contentScale = ContentScale.Fit)
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
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = OmniColors.Dim, modifier = Modifier.size(36.dp))
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
