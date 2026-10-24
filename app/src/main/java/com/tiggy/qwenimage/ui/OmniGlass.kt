package com.tiggy.qwenimage.ui

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun LiquidBackdrop(animated: Boolean) {
    val drift = if (animated) {
        val transition = rememberInfiniteTransition()
        val value = transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse)
        )
        value.value
    } else {
        0f
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(OmniColors.Background)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(OmniColors.Violet.copy(alpha = 0.22f), OmniColors.Violet.copy(alpha = 0.05f), Color.Transparent)
            ),
            radius = size.minDimension * 0.65f,
            center = Offset(size.width * (0.08f + drift * 0.025f), size.height * 0.08f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(OmniColors.Mint.copy(alpha = 0.14f), Color.Transparent)
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * (0.94f - drift * 0.025f), size.height * 0.55f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(OmniColors.Amber.copy(alpha = 0.08f), Color.Transparent)
            ),
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.3f, size.height * (0.96f + drift * 0.015f))
        )
    }
}

@Composable
fun OverlayGlass(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    accent: Color = OmniColors.Violet,
    blurEnabled: Boolean = true,
    radius: Dp = 26.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val canBlur = blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val style = HazeStyle(
        backgroundColor = OmniColors.Background,
        tint = HazeTint(Color(0x66202A35)),
        blurRadius = 22.dp,
        noiseFactor = 0.035f,
        fallbackTint = HazeTint(Color(0xF5171B23))
    )
    Box(
        modifier
            .clip(shape)
            .then(if (canBlur) Modifier.hazeChild(hazeState, style) else Modifier)
            .background(if (canBlur) Color(0x381A2230) else Color(0xF5171B23))
            .glassRim(shape, accent, radius),
        content = content
    )
}

private fun Modifier.glassRim(shape: RoundedCornerShape, accent: Color, radius: Dp) = drawWithCache {
    val corner = CornerRadius(radius.toPx(), radius.toPx())
    val rim = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.56f), Color.White.copy(alpha = 0.12f), accent.copy(alpha = 0.34f), Color.White.copy(alpha = 0.16f))
    )
    val shine = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent), endY = 46.dp.toPx())
    onDrawWithContent {
        drawRoundRect(shine, cornerRadius = corner)
        drawContent()
        drawRoundRect(rim, cornerRadius = corner, style = Stroke(width = 1.1.dp.toPx()))
        drawRoundRect(accent.copy(alpha = 0.12f), cornerRadius = corner, style = Stroke(width = 0.5.dp.toPx()))
    }
}
