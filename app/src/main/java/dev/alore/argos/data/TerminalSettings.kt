package dev.alore.argos.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Terminal color theme. [SYSTEM] follows Android's light/dark mode. */
enum class TerminalTheme(val label: String) {
    DARK("Oscuro"),
    LIGHT("Claro"),
    SOLARIZED_DARK("Solarized Dark"),
    SYSTEM("Según el sistema"),
}

/**
 * Full terminal palette: default foreground/background/cursor, the 16 ANSI colors (0-7 normal,
 * 8-15 bright) and [chrome], a slightly lifted background for the bars around the terminal.
 */
class TerminalPalette(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val chrome: Int,
    val ansi: IntArray,
) {
    init {
        require(ansi.size == 16) { "16 ANSI colors expected" }
    }

    /** True for light backgrounds (status bar icons must then be dark). */
    val isLight: Boolean
        get() {
            val r = (background shr 16) and 0xFF
            val g = (background shr 8) and 0xFF
            val b = background and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000 > 140
        }
}

private fun rgb(vararg colors: Long) = IntArray(colors.size) { (0xFF000000 or colors[it]).toInt() }
private fun argb(color: Long) = (0xFF000000 or color).toInt()

/** Argos' own dark scheme (Tokyo Night-inspired): calm contrast, readable bright colors. */
private val DARK_PALETTE = TerminalPalette(
    foreground = argb(0xC0CAF5),
    background = argb(0x1A1B26),
    cursor = argb(0xC0CAF5),
    chrome = argb(0x16161E),
    ansi = rgb(
        0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
        0x414868, 0xFF899D, 0xB4E57F, 0xF5C47D, 0x8DB4FF, 0xC9ABFF, 0x99DCFF, 0xC0CAF5,
    ),
)

/** Light scheme (One Light-inspired). "White" is kept dark enough to stay visible on light. */
private val LIGHT_PALETTE = TerminalPalette(
    foreground = argb(0x383A42),
    background = argb(0xFAFAFA),
    cursor = argb(0x526FFF),
    chrome = argb(0xF0F0F1),
    ansi = rgb(
        0x383A42, 0xE45649, 0x50A14F, 0xC18401, 0x4078F2, 0xA626A4, 0x0184BC, 0x7F848E,
        0x696C77, 0xCA1243, 0x3E8E3D, 0x986801, 0x2E62D9, 0x8A1D8A, 0x016A94, 0x383A42,
    ),
)

/** Ethan Schoonover's Solarized (dark), official values. */
private val SOLARIZED_DARK_PALETTE = TerminalPalette(
    foreground = argb(0x839496),
    background = argb(0x002B36),
    cursor = argb(0x93A1A1),
    chrome = argb(0x00212B),
    ansi = rgb(
        0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
        0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
    ),
)

fun TerminalTheme.palette(systemIsDark: Boolean): TerminalPalette = when (this) {
    TerminalTheme.SYSTEM -> if (systemIsDark) DARK_PALETTE else LIGHT_PALETTE
    TerminalTheme.DARK -> DARK_PALETTE
    TerminalTheme.LIGHT -> LIGHT_PALETTE
    TerminalTheme.SOLARIZED_DARK -> SOLARIZED_DARK_PALETTE
}

/** Terminal appearance (font size in sp + theme), persisted in SharedPreferences. */
class TerminalSettings(context: Context) {
    private val prefs = context.getSharedPreferences("argos_terminal", Context.MODE_PRIVATE)

    private val _fontSize = MutableStateFlow(prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE))
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _theme = MutableStateFlow(
        TerminalTheme.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: TerminalTheme.DARK,
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
        const val DEFAULT_FONT_SIZE = 13
        private const val KEY_FONT_SIZE = "fontSize"
        private const val KEY_THEME = "theme"
    }
}
