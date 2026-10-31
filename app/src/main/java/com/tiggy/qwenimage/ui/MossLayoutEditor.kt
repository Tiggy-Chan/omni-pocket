package com.tiggy.qwenimage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tiggy.qwenimage.data.MossLayoutItem
import com.tiggy.qwenimage.data.MossLayoutLabel
import com.tiggy.qwenimage.data.MossSettings
import kotlin.math.hypot
import kotlin.math.min

/**
 * Canvas for MOSS layout mode: drag to move a box, drag its bottom-right corner to resize, with a
 * list of the elements below. [settings] supplies the canvas aspect ratio and the layout.
 */
@Composable
internal fun MossLayoutEditor(
    settings: MossSettings,
    onSettings: ((MossSettings) -> MossSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val items = settings.layout.items
    val currentSettings by rememberUpdatedState(settings)
    val currentOnSettings by rememberUpdatedState(onSettings)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var resizing by remember { mutableStateOf(false) }
    val accent = OmniColors.Accent
    val ink = OmniColors.Ink
    val muted = OmniColors.Muted
    val shape = RoundedCornerShape(12.dp)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutAddButton("文字") {
                val next = items + defaultLayoutItem(MossLayoutLabel.Text, items.size)
                selected = items.size
                currentOnSettings { it.copy(layout = it.layout.copy(items = next)) }
            }
            LayoutAddButton("对象") {
                val next = items + defaultLayoutItem(MossLayoutLabel.Object, items.size)
                selected = items.size
                currentOnSettings { it.copy(layout = it.layout.copy(items = next)) }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(settings.local.width.toFloat() / settings.local.height.coerceAtLeast(1).toFloat())
                .clip(shape)
                .background(OmniColors.Field)
                .border(0.5.dp, OmniColors.Line, shape)
                .onSizeChanged { canvasSize = it }
                .pointerInput(settings.local.width, settings.local.height) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val currentItems = currentSettings.layout.items
                            val index = currentItems.indexOfLast { item ->
                                item.rect(canvasSize).contains(offset)
                            }.takeIf { it >= 0 }
                            selected = index
                            resizing = index != null && offset.distanceTo(currentItems[index].rect(canvasSize).bottomRight) < 56f
                        },
                        onDrag = { change, amount ->
                            val index = selected ?: return@detectDragGestures
                            val currentItems = currentSettings.layout.items
                            val item = currentItems.getOrNull(index) ?: return@detectDragGestures
                            val dx = (amount.x / canvasSize.width.coerceAtLeast(1)).toDouble()
                            val dy = (amount.y / canvasSize.height.coerceAtLeast(1)).toDouble()
                            val updated = if (resizing) item.resized(dx, dy) else item.moved(dx, dy)
                            currentOnSettings { state ->
                                state.copy(layout = state.layout.copy(items = state.layout.items.mapIndexed { i, value -> if (i == index) updated else value }))
                            }
                            change.consume()
                        }
                    )
                }
                .semantics { contentDescription = "布局画布。拖动移动元素，拖动右下角调整大小。" }
        ) {
            currentSettings.layout.items.forEachIndexed { index, item ->
                val rect = item.rect(size)
                val color = if (item.label == MossLayoutLabel.Text) accent else muted
                val isSelected = selected == index
                drawRect(color.copy(alpha = 0.14f), rect.topLeft, rect.size)
                drawRect(if (isSelected) ink else color, rect.topLeft, rect.size, style = Stroke(width = if (isSelected) 3f else 2f))
                if (isSelected) drawCircle(ink, radius = 8f, center = rect.bottomRight)
            }
        }
        Text("${items.size} 个元素", color = OmniColors.Dim, style = MaterialTheme.typography.labelSmall)
        items.forEachIndexed { index, item ->
            val isSelected = selected == index
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) OmniColors.Selected else Color.Transparent)
                    .clickable(role = Role.Button) { selected = index }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${index + 1}. ${if (item.label == MossLayoutLabel.Text) "文字" else "对象"} · ${item.caption}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) OmniColors.Ink else OmniColors.Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        selected?.let { index ->
            items.getOrNull(index)?.let { item ->
                OmniTextField(
                    value = item.caption,
                    onValueChange = { value ->
                        currentOnSettings { state ->
                            state.copy(layout = state.layout.copy(items = state.layout.items.mapIndexed { i, old -> if (i == index) old.copy(caption = value) else old }))
                        }
                    },
                    label = "元素描述",
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (item.label == MossLayoutLabel.Text) {
                    OmniTextField(
                        value = item.text,
                        onValueChange = { value ->
                            currentOnSettings { state ->
                                state.copy(layout = state.layout.copy(items = state.layout.items.mapIndexed { i, old -> if (i == index) old.copy(text = value) else old }))
                            }
                        },
                        label = "要渲染的文字",
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(
                    onClick = {
                        currentOnSettings { state ->
                            state.copy(layout = state.layout.copy(items = state.layout.items.filterIndexed { i, _ -> i != index }))
                        }
                        selected = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = OmniColors.Danger)
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除元素")
                }
            }
        }
    }
}

@Composable
private fun LayoutAddButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = OmniColors.Field,
        modifier = Modifier.heightIn(min = 40.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Add, null, tint = OmniColors.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = OmniColors.Ink, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun defaultLayoutItem(label: MossLayoutLabel, index: Int): MossLayoutItem {
    val offset = (index % 5) * 0.04
    return MossLayoutItem(
        label = label,
        caption = if (label == MossLayoutLabel.Text) "标题文字" else "画面主体",
        text = if (label == MossLayoutLabel.Text) "TEXT" else "",
        left = 0.15 + offset,
        top = 0.15 + offset,
        right = 0.65 + offset,
        bottom = 0.45 + offset
    )
}

private fun MossLayoutItem.rect(size: Size): Rect = Rect(
    (left * size.width).toFloat(),
    (top * size.height).toFloat(),
    (right * size.width).toFloat(),
    (bottom * size.height).toFloat()
)

private fun MossLayoutItem.rect(size: IntSize): Rect = rect(Size(size.width.toFloat(), size.height.toFloat()))

private fun MossLayoutItem.moved(dx: Double, dy: Double): MossLayoutItem {
    val width = right - left
    val height = bottom - top
    val newLeft = (left + dx).coerceIn(0.0, 1.0 - width)
    val newTop = (top + dy).coerceIn(0.0, 1.0 - height)
    return copy(left = newLeft, top = newTop, right = newLeft + width, bottom = newTop + height)
}

private fun MossLayoutItem.resized(dx: Double, dy: Double): MossLayoutItem = copy(
    right = (right + dx).coerceIn(min(1.0, left + 0.02), 1.0),
    bottom = (bottom + dy).coerceIn(min(1.0, top + 0.02), 1.0)
)

private fun Offset.distanceTo(other: Offset): Float = hypot(x - other.x, y - other.y)
