package com.example.vintageexposurecalculator.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Define the Art Deco color scheme
private val ArtDecoColorScheme = darkColorScheme(
    primary = ArtDecoGold,
    onPrimary = ArtDecoBlack,
    background = ArtDecoBlack,
    onBackground = ArtDecoCream,
    surface = ArtDecoCream, // Used for Card backgrounds
    onSurface = ArtDecoBlack, // Used for text on Cards
    outline = ArtDecoGold,
    error = ArtDecoError,
    onError = ArtDecoCream
)

@Composable
fun VintageExposureCalculatorTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = ArtDecoColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false // Dark background, so light status bar icons
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
