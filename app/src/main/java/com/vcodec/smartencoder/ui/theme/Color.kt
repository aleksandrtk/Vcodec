package com.vcodec.smartencoder.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens for the app theme.
 */
@Immutable
data class AppColors(
    val backgroundTop: Color,
    val backgroundMid: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val surfaceTransparent: Color,
    val rowBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentDim: Color,
    val border: Color,
    val unchecked: Color,
    val success: Color,
    val scrim: Color,
    val appBarGlass: Color,
    val navGlass: Color,
    val onAccent: Color,
    val isDark: Boolean
)

/** Dark theme (current cosmic slate look). */
val DarkAppColors = AppColors(
    backgroundTop = Color(0xFF070A13),
    backgroundMid = Color(0xFF0F172A),
    backgroundBottom = Color(0xFF1E1E38),
    surface = Color(0xFF1E293B),
    surfaceTransparent = Color(0x3D1E293B),
    rowBackground = Color(0xFF1E293B),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    accent = Color(0xFF06B6D4),
    accentDim = Color(0xFF164E63),
    border = Color(0xFF334155),
    unchecked = Color(0xFF334155),
    success = Color(0xFF4ADE80),
    scrim = Color(0xFF0B1120),
    appBarGlass = Color(0x7F0F172A),
    navGlass = Color(0xCC0F172A),
    onAccent = Color.Black,
    isDark = true
)

/**
 * Light theme in Samsung One UI style: clean white surfaces on soft gray,
 * Samsung blue accent, near-black text, subtle borders.
 */
val LightAppColors = AppColors(
    backgroundTop = Color(0xFFF7F8FA),
    backgroundMid = Color(0xFFF2F4F7),
    backgroundBottom = Color(0xFFEAEEF3),
    surface = Color(0xFFFFFFFF),
    surfaceTransparent = Color(0xFFFFFFFF),
    rowBackground = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF171A1C),
    textSecondary = Color(0xFF6B7178),
    accent = Color(0xFF0381FE),      // One UI signature blue
    accentDim = Color(0xFFE3F0FF),
    border = Color(0xFFE1E4E8),
    unchecked = Color(0xFFD5D9DE),
    success = Color(0xFF16A34A),
    scrim = Color(0xFFF2F4F7),
    appBarGlass = Color(0xCCF7F8FA),
    navGlass = Color(0xF2FFFFFF),
    onAccent = Color.White,
    isDark = false
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

// Composable accessors keep the historical token names working across the UI code
// while resolving to the active theme at runtime.

val DarkBackground: Color @Composable get() = LocalAppColors.current.backgroundMid
val DarkSurface: Color @Composable get() = LocalAppColors.current.surface
val PrimaryCyan: Color @Composable get() = LocalAppColors.current.accent
val TextWhite: Color @Composable get() = LocalAppColors.current.textPrimary
val TextGray: Color @Composable get() = LocalAppColors.current.textSecondary
val AccentEmerald: Color @Composable get() = LocalAppColors.current.success
val SuccessColor: Color @Composable get() = LocalAppColors.current.success

val AlertAmber = Color(0xFFF59E0B)     // Thermal warning orange
val AlertRed = Color(0xFFEF4444)       // Thermal hot red
val CardGlow = Color(0x1F06B6D4)
