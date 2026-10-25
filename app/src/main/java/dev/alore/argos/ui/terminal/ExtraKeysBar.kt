package dev.alore.argos.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alore.argos.data.TerminalPalette

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
fun ExtraKeysBar(palette: TerminalPalette, modifiers: StickyModifiers, enabled: Boolean, onKey: (ExtraKey) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(palette.chrome))
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Key("CTRL", "Control (siguiente tecla)", palette, enabled, active = modifiers.ctrl) { modifiers.ctrl = !modifiers.ctrl }
        Key("ALT", "Alt (siguiente tecla)", palette, enabled, active = modifiers.alt) { modifiers.alt = !modifiers.alt }
        for (key in KEYS) Key(key.label, key.description, palette, enabled) { onKey(key) }
    }
}

/** A compact, terminal-colored key: tinted surface, accent fill when a sticky modifier is armed. */
@Composable
private fun Key(
    label: String,
    description: String,
    palette: TerminalPalette,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = Color(palette.foreground)
    val accent = Color(palette.ansi[4])
    val container = if (active) accent else fg.copy(alpha = 0.08f)
    val content = when {
        active -> Color(palette.background)
        enabled -> fg
        else -> fg.copy(alpha = 0.35f)
    }
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontFamily = TerminalFont, fontSize = 13.sp)
    }
}
