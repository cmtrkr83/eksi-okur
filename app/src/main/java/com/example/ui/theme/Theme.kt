package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val EksiLightColorScheme = lightColorScheme(
    primary = EksiGreenLight,
    onPrimary = EksiOnGreenLight,
    primaryContainer = EksiGreenContainerLight,
    onPrimaryContainer = EksiOnGreenContainerLight,
    secondary = SukelaAmberLight,
    onSecondary = EksiOnGreenLight,
    secondaryContainer = SukelaContainerLight,
    onSecondaryContainer = PaperInk,
    tertiary = EksiGreenDark,
    background = PaperBackground,
    onBackground = PaperInk,
    surface = PaperSurface,
    onSurface = PaperInk,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperInkMuted,
    surfaceContainerLow = PaperBackground,
    surfaceContainer = PaperSurfaceContainer,
    surfaceContainerHigh = PaperSurfaceVariant,
    outline = PaperOutline,
    outlineVariant = PaperOutline.copy(alpha = 0.6f),
    error = ErrorLight,
    errorContainer = ErrorContainerLight
)

private val EksiDarkColorScheme = darkColorScheme(
    primary = EksiGreenDark,
    onPrimary = EksiOnGreenDark,
    primaryContainer = EksiGreenContainerDark,
    onPrimaryContainer = EksiOnGreenContainerDark,
    secondary = SukelaAmberDark,
    onSecondary = EksiOnGreenDark,
    secondaryContainer = SukelaContainerDark,
    onSecondaryContainer = InkText,
    tertiary = EksiGreenLight,
    background = InkBackground,
    onBackground = InkText,
    surface = InkSurface,
    onSurface = InkText,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = InkTextMuted,
    surfaceContainerLow = InkBackground,
    surfaceContainer = InkSurfaceContainer,
    surfaceContainerHigh = InkSurfaceVariant,
    outline = InkOutline,
    outlineVariant = InkOutline.copy(alpha = 0.7f),
    error = ErrorDark,
    errorContainer = ErrorContainerDark
)

@Composable
fun EksiOkurTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    entryFontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> EksiDarkColorScheme
        else -> EksiLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = buildEksiTypography(entryFontScale),
        content = content
    )
}
