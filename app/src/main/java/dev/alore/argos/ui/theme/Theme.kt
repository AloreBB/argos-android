package dev.alore.argos.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BA9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF575E71),
    tertiary = Color(0xFF00696B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF002B74),
    primaryContainer = Color(0xFF21428F),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFBFC6DC),
    tertiary = Color(0xFF4CDADB),
)

/** Color for "attached"/"connected" indicators (a semantic green that reads in both themes). */
val Connected = Color(0xFF2E9E5B)

@Composable
fun ArgosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
