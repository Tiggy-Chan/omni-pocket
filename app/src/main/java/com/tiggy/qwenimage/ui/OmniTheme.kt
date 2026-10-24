package com.tiggy.qwenimage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OmniColors {
    val Background = Color(0xFF090B10)
    val Content = Color(0xF014171F)
    val ContentStrong = Color(0xFF181C25)
    val Ink = Color(0xFFF5F4F7)
    val Muted = Color(0xFFAAA8B2)
    val Dim = Color(0xFF85838E)
    val Violet = Color(0xFFC6B3F4)
    val VioletDeep = Color(0xFF7663B5)
    val Mint = Color(0xFF9AD6C5)
    val Amber = Color(0xFFE8BC75)
    val Rose = Color(0xFFF0A6B4)
    val Border = Color(0x16FFFFFF)
}

@Composable
fun OmniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OmniColors.Violet,
            secondary = OmniColors.Mint,
            background = OmniColors.Background,
            surface = OmniColors.ContentStrong,
            surfaceVariant = OmniColors.ContentStrong,
            onSurface = OmniColors.Ink,
            onBackground = OmniColors.Ink,
            onPrimary = Color(0xFF17121F),
            error = OmniColors.Rose
        ),
        content = content
    )
}
