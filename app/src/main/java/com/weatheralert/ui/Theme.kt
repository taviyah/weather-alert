package com.weatheralert.ui

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF0B4F6C),
    onPrimary = Color.White,
    secondary = Color(0xFF01718A),
    tertiary = Color(0xFFC77800),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FC6E0),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFF6FD3E8),
    tertiary = Color(0xFFFFC857),
)

/**
 * Pure black on pure white with no tonal surfaces at all.
 *
 * E Ink renders greyscale by dithering, so Material's tinted "surface variant" greys turn into
 * visible stipple patterns that read as texture rather than as a background. Every container here
 * is white and gets separated by a border instead.
 */
private val EInk = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    tertiary = Color.Black,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    // Not a light grey: on e-paper "secondary text" has to stay legible, so it is only slightly
    // lifted off black rather than the usual 60% grey.
    onSurfaceVariant = Color(0xFF2B2B2B),
    outline = Color.Black,
    outlineVariant = Color(0xFF7A7A7A),
    error = Color.Black,
    onError = Color.White,
    // The container roles are what FABs, chips and buttons actually paint with. Leaving them at
    // their defaults left a lavender floating button sitting in an otherwise monochrome page.
    primaryContainer = Color.Black,
    onPrimaryContainer = Color.White,
    secondaryContainer = Color.Black,
    onSecondaryContainer = Color.White,
    tertiaryContainer = Color.Black,
    onTertiaryContainer = Color.White,
    errorContainer = Color.White,
    onErrorContainer = Color.Black,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceBright = Color.White,
    surfaceDim = Color.White,
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    scrim = Color.Black,
)

@Composable
fun WeatherAlertTheme(
    eInk: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        eInk -> EInk
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> Dark
        else -> Light
    }

    CompositionLocalProvider(
        LocalEInk provides eInk,
        // Ripples are a per-frame animation; on e-paper each frame is a full panel refresh.
        LocalIndication provides if (eInk) NoIndication else LocalIndication.current,
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
