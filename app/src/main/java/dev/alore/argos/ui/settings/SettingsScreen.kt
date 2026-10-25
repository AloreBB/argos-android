package dev.alore.argos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alore.argos.ArgosApplication
import dev.alore.argos.BuildConfig
import dev.alore.argos.data.TerminalSettings
import dev.alore.argos.data.TerminalTheme
import dev.alore.argos.data.TerminalPalette
import dev.alore.argos.data.palette
import dev.alore.argos.ui.terminal.TerminalFont
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = (LocalContext.current.applicationContext as ArgosApplication).container.terminalSettings
    val fontSize by settings.fontSize.collectAsStateWithLifecycle()
    val theme by settings.theme.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Terminal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tamaño de fuente", Modifier.weight(1f))
                Text("$fontSize sp", fontFamily = FontFamily.Monospace)
            }
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { settings.setFontSize(it.toInt()) },
                valueRange = TerminalSettings.MIN_FONT_SIZE.toFloat()..TerminalSettings.MAX_FONT_SIZE.toFloat(),
                steps = TerminalSettings.MAX_FONT_SIZE - TerminalSettings.MIN_FONT_SIZE - 1,
            )
            TerminalPreview(theme.palette(isSystemInDarkTheme()), fontSize)
            Text(
                "También puedes ajustarlo pellizcando el terminal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Tema", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
            Column(Modifier.selectableGroup()) {
                for (option in TerminalTheme.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == theme, onClick = { settings.setTheme(option) }, role = Role.RadioButton)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == theme, onClick = null)
                        Text(option.label, Modifier.padding(start = 12.dp).weight(1f))
                        Swatches(option.palette(isSystemInDarkTheme()))
                    }
                }
            }

            Text("Acerca de", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
            Text("Argos ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Gestor de sesiones tmux remotas por SSH. Terminal basado en Termux (Apache 2.0).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the terminal will look like: same font, size and palette. */
@Composable
private fun TerminalPreview(palette: TerminalPalette, fontSize: Int) {
    val fg = Color(palette.foreground)
    fun c(index: Int) = Color(palette.ansi[index])
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c(2))) { append("kevin@server") }
        append(":")
        withStyle(SpanStyle(color = c(4))) { append("~/proyectos") }
        append("$ tmux ls\n")
        append("main: 3 windows ")
        withStyle(SpanStyle(color = c(8))) { append("(attached)") }
        append("\n")
        withStyle(SpanStyle(color = c(1))) { append("error") }
        append(" · ")
        withStyle(SpanStyle(color = c(3))) { append("warn") }
        append(" · ")
        withStyle(SpanStyle(color = c(5))) { append("info") }
        append(" · ")
        withStyle(SpanStyle(color = c(6))) { append("debug") }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(palette.background))
            .border(1.dp, fg.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(text, color = fg, fontFamily = TerminalFont, fontSize = fontSize.sp, lineHeight = (fontSize * 1.3f).sp)
    }
}

@Composable
private fun Swatches(palette: TerminalPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(palette.background)).border(1.dp, Color(0x33000000), CircleShape))
        for (index in listOf(1, 2, 3, 4, 5)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(palette.ansi[index])))
        }
    }
}
