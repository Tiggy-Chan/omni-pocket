package com.tiggy.qwenimage.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

/**
 * Floating glass for overlays only (composer, top bar, sheets). On Android 12+ it samples the
 * content behind it through Haze; older devices and [blurEnabled] = false fall back to a nearly
 * opaque surface. A thin edge and a soft top highlight separate it without colored rims.
 */
@Composable
fun OverlayGlass(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") accent: Color = Color.Unspecified,
    blurEnabled: Boolean = true,
    radius: Dp = 22.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalOmniPalette.current
    val shape = RoundedCornerShape(radius)
    val canBlur = blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val style = HazeStyle(
        backgroundColor = palette.background,
        tint = HazeTint(palette.glass),
        blurRadius = 24.dp,
        noiseFactor = 0.02f,
        fallbackTint = HazeTint(palette.glassFallback)
    )
    val highlight = if (palette.dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.7f)
    Box(
        modifier
            .shadow(if (palette.dark) 0.dp else 12.dp, shape, ambientColor = Color(0x14202630), spotColor = Color(0x14202630))
            .clip(shape)
            .then(if (canBlur) Modifier.hazeChild(hazeState, style) else Modifier.background(palette.glassFallback))
            .drawWithCache {
                val sheen = Brush.verticalGradient(listOf(highlight, Color.Transparent), endY = 28.dp.toPx())
                onDrawBehind { drawRect(sheen) }
            }
            .border(0.5.dp, palette.glassEdge, shape),
        content = content
    )
}
