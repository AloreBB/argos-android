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
            Text(
                "\$ tmux attach -t main",
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        Text(option.label, Modifier.padding(start = 12.dp))
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

