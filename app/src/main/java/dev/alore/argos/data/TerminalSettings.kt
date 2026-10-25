package dev.alore.argos.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Terminal color theme. [SYSTEM] follows Android's light/dark mode. */
enum class TerminalTheme(val label: String) {
    SYSTEM("Sistema"),
    DARK("Oscuro"),
    LIGHT("Claro"),
    SOLARIZED_DARK("Solarized Dark"),
}

/** ARGB colors applied to the emulator's default foreground/background/cursor. */
data class TerminalPalette(val foreground: Int, val background: Int, val cursor: Int)

fun TerminalTheme.palette(systemIsDark: Boolean): TerminalPalette = when (this) {
    TerminalTheme.SYSTEM -> if (systemIsDark) TerminalTheme.DARK.palette(true) else TerminalTheme.LIGHT.palette(false)
    TerminalTheme.DARK -> TerminalPalette(0xFFEBEBEB.toInt(), 0xFF141414.toInt(), 0xFFEBEBEB.toInt())
    TerminalTheme.LIGHT -> TerminalPalette(0xFF1A1A1A.toInt(), 0xFFFCFCFC.toInt(), 0xFF1A1A1A.toInt())
    // Solarized base0 on base03.
    TerminalTheme.SOLARIZED_DARK -> TerminalPalette(0xFF839496.toInt(), 0xFF002B36.toInt(), 0xFF93A1A1.toInt())
}

/** Terminal appearance (font size in sp + theme), persisted in SharedPreferences. */
class TerminalSettings(context: Context) {
    private val prefs = context.getSharedPreferences("argos_terminal", Context.MODE_PRIVATE)

    private val _fontSize = MutableStateFlow(prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _theme = MutableStateFlow(
        TerminalTheme.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: TerminalTheme.SYSTEM,
    )
    val theme: StateFlow<TerminalTheme> = _theme.asStateFlow()

    fun setFontSize(sp: Int) {
        val value = sp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        _fontSize.value = value
        prefs.edit { putInt(KEY_FONT_SIZE, value) }
    }

    fun setTheme(theme: TerminalTheme) {
        _theme.value = theme
        prefs.edit { putString(KEY_THEME, theme.name) }
    }

    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 28
        const val DEFAULT_FONT_SIZE = 12
        private const val KEY_FONT_SIZE = "fontSize"
        private const val KEY_THEME = "theme"
    }
}
