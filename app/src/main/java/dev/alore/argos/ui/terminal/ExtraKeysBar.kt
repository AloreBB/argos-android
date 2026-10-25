package dev.alore.argos.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** A key of the extra row: an Android key code (respects cursor/keypad app modes), a char, or raw bytes. */
sealed interface ExtraKey {
    val label: String
    val description: String

    data class Code(override val label: String, override val description: String, val keyCode: Int) : ExtraKey
    data class Char(override val label: String, override val description: String, val char: kotlin.Char) : ExtraKey
    class Raw(override val label: String, override val description: String, val bytes: ByteArray) : ExtraKey
}

/** What the soft keyboard lacks for tmux/shell work. ^B is tmux's default prefix. */
private val KEYS: List<ExtraKey> = listOf(
    ExtraKey.Code("ESC", "Escape", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Code("TAB", "Tabulador", KeyEvent.KEYCODE_TAB),
    ExtraKey.Raw("^B", "Prefijo de tmux (Ctrl+B)", byteArrayOf(0x02)),
    ExtraKey.Code("←", "Flecha izquierda", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Code("↓", "Flecha abajo", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Code("↑", "Flecha arriba", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Code("→", "Flecha derecha", KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Code("PGUP", "Re Pág", KeyEvent.KEYCODE_PAGE_UP),
    ExtraKey.Code("PGDN", "Av Pág", KeyEvent.KEYCODE_PAGE_DOWN),
    ExtraKey.Code("HOME", "Inicio", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Code("END", "Fin", KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Char("-", "Guion", '-'),
    ExtraKey.Char("/", "Barra", '/'),
    ExtraKey.Char("|", "Barra vertical", '|'),
    ExtraKey.Char("~", "Virgulilla", '~'),
)

@Composable
fun ExtraKeysBar(modifiers: StickyModifiers, enabled: Boolean, onKey: (ExtraKey) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToggleKey("CTRL", "Control (siguiente tecla)", modifiers.ctrl, enabled) { modifiers.ctrl = !modifiers.ctrl }
            ToggleKey("ALT", "Alt (siguiente tecla)", modifiers.alt, enabled) { modifiers.alt = !modifiers.alt }
            for (key in KEYS) {
                OutlinedButton(
                    onClick = { onKey(key) },
                    enabled = enabled,
                    contentPadding = KEY_PADDING,
                    modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 44.dp).semantics { contentDescription = key.description },
                ) { KeyLabel(key.label) }
            }
        }
    }
}

@Composable
private fun ToggleKey(label: String, description: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 52.dp).semantics { contentDescription = description }
    if (active) {
        FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = KEY_PADDING, modifier = modifier) { KeyLabel(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = KEY_PADDING, modifier = modifier) { KeyLabel(label) }
    }
}

@Composable
private fun KeyLabel(text: String) = Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)

private val KEY_PADDING = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
