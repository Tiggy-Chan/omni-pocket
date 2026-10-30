package com.tiggy.qwenimage.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.tiggy.qwenimage.data.ThemeMode

@Immutable
data class OmniPalette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val raised: Color,
    val sidebar: Color,
    val field: Color,
    val selected: Color,
    val ink: Color,
    val muted: Color,
    val dim: Color,
    val line: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val glass: Color,
    val glassFallback: Color,
    val glassEdge: Color,
    val scrim: Color
)

val LightPalette = OmniPalette(
    dark = false,
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF0F2F5),
    field = Color(0xFFF2F4F7),
    selected = Color(0xFFE3E8EF),
    ink = Color(0xFF1F2328),
    muted = Color(0xFF5F6670),
    dim = Color(0xFF8C939C),
    line = Color(0xFFE2E5E9),
    accent = Color(0xFF3A6FE0),
    accentSoft = Color(0xFFE7EEFC),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFC4384C),
    warning = Color(0xFFA86A12),
    success = Color(0xFF2F7D55),
    glass = Color(0xB8FFFFFF),
    glassFallback = Color(0xF7FFFFFF),
    glassEdge = Color(0x1A1F2328),
    scrim = Color(0x33202630)
)

val DarkPalette = OmniPalette(
    dark = true,
    background = Color(0xFF0F1115),
    surface = Color(0xFF16191E),
    raised = Color(0xFF1C2026),
    sidebar = Color(0xFF121418),
    field = Color(0xFF1A1D23),
    selected = Color(0xFF242932),
    ink = Color(0xFFECEEF1),
    muted = Color(0xFFA2A8B1),
    dim = Color(0xFF7B828C),
    line = Color(0xFF262A31),
    accent = Color(0xFF7AA2FF),
    accentSoft = Color(0xFF1D2740),
    onAccent = Color(0xFF0A1020),
    danger = Color(0xFFF07A8A),
    warning = Color(0xFFE2B45A),
    success = Color(0xFF6CCB97),
    glass = Color(0x9E1C2026),
    glassFallback = Color(0xF71C2026),
    glassEdge = Color(0x1FFFFFFF),
    scrim = Color(0x66000000)
)

val LocalOmniPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Semantic colors for the current light/dark palette. The older names (Violet, Mint, Amber, Rose)
 * map onto the single accent and the status colors so every screen shares one restrained scheme.
 */
object OmniColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.surface
    val Raised: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.raised
    val Sidebar: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.sidebar
    val Field: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.field
    val Selected: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.selected
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.ink
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.muted
    val Dim: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.dim
    val Line: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.line
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.accent
    val AccentSoft: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.accentSoft
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.onAccent
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.danger
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.warning
    val Success: Color @Composable @ReadOnlyComposable get() = LocalOmniPalette.current.success

    val Content: Color @Composable @ReadOnlyComposable get() = Surface
    val ContentStrong: Color @Composable @ReadOnlyComposable get() = Raised
    val Border: Color @Composable @ReadOnlyComposable get() = Line
    val Violet: Color @Composable @ReadOnlyComposable get() = Accent
    val VioletDeep: Color @Composable @ReadOnlyComposable get() = Accent
    val Mint: Color @Composable @ReadOnlyComposable get() = Accent
    val Amber: Color @Composable @ReadOnlyComposable get() = Warning
    val Rose: Color @Composable @ReadOnlyComposable get() = Danger
}

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun OmniTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val palette = if (mode.isDark()) DarkPalette else LightPalette
    val base = if (palette.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.ink,
        secondary = palette.accent,
        onSecondary = palette.onAccent,
        secondaryContainer = palette.selected,
        onSecondaryContainer = palette.ink,
        background = palette.background,
        onBackground = palette.ink,
        surface = palette.surface,
        onSurface = palette.ink,
        surfaceVariant = palette.field,
        onSurfaceVariant = palette.muted,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = palette.surface,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.raised,
        surfaceContainerHighest = palette.raised,
        outline = palette.line,
        outlineVariant = palette.line,
        error = palette.danger,
        scrim = palette.scrim
    )
    CompositionLocalProvider(LocalOmniPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
