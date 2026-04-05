package com.kousoyu.drift.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DriftDarkColorScheme = darkColorScheme(
    primary              = DriftAccent,
    onPrimary            = DarkOnBackground,
    primaryContainer     = DriftAccentDark,
    onPrimaryContainer   = DarkOnBackground,
    background           = DarkBackground,
    onBackground         = DarkOnBackground,
    surface              = DarkSurface,
    onSurface            = DarkOnSurface,
    surfaceVariant       = DarkSurfaceVariant,
    onSurfaceVariant     = DarkOnSurfaceVar,
    outline              = DarkOutline,
    secondary            = DriftAccent,
    onSecondary          = DarkOnBackground,
)

private val DriftLightColorScheme = lightColorScheme(
    primary              = LightAccent,
    onPrimary            = LightBackground,
    primaryContainer     = LightSurfaceVariant,
    onPrimaryContainer   = LightOnBackground,
    background           = LightBackground,
    onBackground         = LightOnBackground,
    surface              = LightSurface,
    onSurface            = LightOnSurface,
    surfaceVariant       = LightSurfaceVariant,
    onSurfaceVariant     = LightOnSurfaceVar,
    outline              = LightOutline,
    secondary            = LightAccent,
    onSecondary          = LightBackground,
)

@Composable
fun DriftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // We deliberately bypass Android 12+ Dynamic Color so that our carefully crafted
    // futuristic palette is always applied, regardless of system wallpaper color.
    val colorScheme = if (darkTheme) DriftDarkColorScheme else DriftLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}