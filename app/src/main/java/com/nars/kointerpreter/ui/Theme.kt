package com.nars.kointerpreter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Araso brand colors, taken from the app icon. */
object Brand {
    val Navy = Color(0xFF070C1F)
    val Cyan = Color(0xFF22D3EE)
    val Blue = Color(0xFF3B82F6)
    val Violet = Color(0xFF8B5CF6)
    val Ear = Color(0xFFF4F6FF)

    /** The icon's sound-wave gradient, for the main buttons and highlights. */
    val Gradient = Brush.horizontalGradient(listOf(Cyan, Blue, Violet))
}

private val Araso = darkColorScheme(
    primary = Color(0xFF6E7BFA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF26306B),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Brand.Cyan,
    onSecondary = Brand.Navy,
    secondaryContainer = Color(0xFF113A4A),
    onSecondaryContainer = Color(0xFFCFF6FD),
    tertiary = Brand.Violet,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF35246B),
    onTertiaryContainer = Color(0xFFEDE5FF),
    background = Brand.Navy,
    onBackground = Brand.Ear,
    surface = Brand.Navy,
    onSurface = Brand.Ear,
    surfaceVariant = Color(0xFF1C2547),
    onSurfaceVariant = Color(0xFFA3ACCD),
    surfaceContainerLowest = Color(0xFF050916),
    surfaceContainerLow = Color(0xFF0D1430),
    surfaceContainer = Color(0xFF111938),
    surfaceContainerHigh = Color(0xFF161F42),
    surfaceContainerHighest = Color(0xFF1C2650),
    outline = Color(0xFF465079),
    outlineVariant = Color(0xFF283157),
    error = Color(0xFFFF6B81),
    onError = Color.White,
    errorContainer = Color(0xFF4A1726),
    onErrorContainer = Color(0xFFFFD9DF),
)

/** Always the dark navy look of the icon, whatever the system theme. */
@Composable
fun InterpreterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Araso, content = content)
}
