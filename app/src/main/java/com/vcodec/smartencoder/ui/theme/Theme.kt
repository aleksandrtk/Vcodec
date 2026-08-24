package com.vcodec.smartencoder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun SmartEncoderTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors

    CompositionLocalProvider(LocalAppColors provides appColors) {
        val scheme = if (darkTheme) {
            darkColorScheme(
                primary = appColors.accent,
                onPrimary = appColors.onAccent,
                secondary = appColors.success,
                background = appColors.backgroundMid,
                surface = appColors.surface,
                onBackground = appColors.textPrimary,
                onSurface = appColors.textPrimary
            )
        } else {
            lightColorScheme(
                primary = appColors.accent,
                onPrimary = appColors.onAccent,
                secondary = appColors.success,
                background = appColors.backgroundMid,
                surface = appColors.surface,
                onBackground = appColors.textPrimary,
                onSurface = appColors.textPrimary
            )
        }

        MaterialTheme(
            colorScheme = scheme,
            content = content
        )
    }
}
