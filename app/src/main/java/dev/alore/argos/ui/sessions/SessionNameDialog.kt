package dev.alore.argos.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.alore.argos.model.SessionNameValidator
import dev.alore.argos.ssh.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface SessionNameMode {
    val title: String
    val actionLabel: String
    val initialName: String
    val hint: String

    data object Create : SessionNameMode {
        override val title = "Nueva sesión"
        override val actionLabel = "Crear"
        override val initialName = ""
        override val hint = "Escribe \"grupo/nombre\" para agruparla, p. ej. \"magic-agents/backend\"."
    }

    data class Rename(val current: String) : SessionNameMode {
        override val title = "Renombrar sesión"
        override val actionLabel = "Renombrar"
        override val initialName = current
        override val hint = "Usa \"grupo/nombre\" para moverla de grupo, p. ej. \"magic-agents/backend\"."
    }
}

/**
 * Single dialog to create or rename a tmux session: live validation (empty, ':' '.', control
 * chars, leading '-') and server errors (e.g. duplicate name) shown inline. The typed name is kept
 * on failure.
 */
@Composable
fun SessionNameDialog(
    mode: SessionNameMode,
    serverName: String,
    onSubmit: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(mode.initialName) }
    var submitting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val inlineMessage = serverError ?: SessionNameValidator.liveValidationMessage(name)
    val unchanged = mode is SessionNameMode.Rename && name.trim() == mode.current
    val canSubmit = !submitting && SessionNameValidator.isValid(name) && !unchanged

    fun submit() {
        if (!canSubmit) return
        submitting = true
        serverError = null
        scope.launch {
            try {
                onSubmit(name)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                serverError = e.userMessage
                submitting = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(mode.title) },
        text = {
            Column {
                Text(
                    "Servidor: $serverName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; serverError = null },
                    label = { Text(if (mode is SessionNameMode.Create) "Nombre de la sesión" else "Nuevo nombre") },
                    singleLine = true,
                    enabled = !submitting,
                    isError = inlineMessage != null,
                    supportingText = { Text(inlineMessage ?: mode.hint) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                TextButton(onClick = ::submit, enabled = canSubmit) { Text(mode.actionLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancelar") }
        },
    )
}
