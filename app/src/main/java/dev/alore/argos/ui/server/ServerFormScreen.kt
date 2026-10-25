package dev.alore.argos.ui.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.alore.argos.ServerSecrets
import dev.alore.argos.model.AuthMethod
import dev.alore.argos.model.Server
import dev.alore.argos.ssh.SshConfig
import dev.alore.argos.ssh.SshService
import dev.alore.argos.ssh.userMessage
import dev.alore.argos.ui.sessions.SessionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Private keys are small text files; anything bigger is not a key. */
private const val MAX_KEY_BYTES = 64 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerFormScreen(serverId: String?, viewModel: SessionsViewModel, onDone: () -> Unit) {
    val existing = remember(serverId) { serverId?.let { id -> viewModel.servers.value.firstOrNull { it.server.id == id }?.server } }
    val existingSecrets = remember(serverId) { serverId?.let(viewModel::secretsOf) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    var authMethod by rememberSaveable { mutableStateOf(existing?.authMethod ?: AuthMethod.KEY) }
    // Secrets stay in memory only (not in saved instance state).
    var keyText by remember { mutableStateOf(existingSecrets?.privateKey) }
    var keyLabel by rememberSaveable { mutableStateOf(existing?.keyLabel.orEmpty()) }
    var passphrase by remember { mutableStateOf(existingSecrets?.passphrase.orEmpty()) }
    var password by remember { mutableStateOf(existingSecrets?.password.orEmpty()) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { readKeyFile(context, uri) }
            result.onSuccess { (label, text) ->
                keyText = text
                keyLabel = label
                keyError = null
            }.onFailure { keyError = it.message }
        }
    }

    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val baseValid = name.isNotBlank() && host.isNotBlank() && username.isNotBlank() && portValue != null
    val canSave = !saving && baseValid && when (authMethod) {
        AuthMethod.KEY -> !keyText.isNullOrBlank()
        AuthMethod.PASSWORD -> password.isNotEmpty()
    }

    fun save() {
        val portNumber = portValue ?: return
        if (!canSave) return
        val server = Server(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            host = host.trim(),
            port = portNumber,
            username = username.trim(),
            authMethod = authMethod,
            keyLabel = if (authMethod == AuthMethod.KEY) keyLabel else "",
        )
        val secrets = when (authMethod) {
            AuthMethod.KEY -> ServerSecrets(keyText, passphrase.ifEmpty { null }, null)
            AuthMethod.PASSWORD -> ServerSecrets(null, null, password)
        }
        saving = true
        scope.launch {
            if (authMethod == AuthMethod.KEY) {
                // Validate key + passphrase locally so mistakes show here, not as a vague connection error.
                val error = withContext(Dispatchers.Default) {
                    runCatching {
                        SshService.loadKey(SshConfig(server.host, server.port, server.username, AuthMethod.KEY, keyText, secrets.passphrase))
                    }.exceptionOrNull()
                }
                if (error != null) {
                    keyError = error.userMessage
                    saving = false
                    return@launch
                }
            }
            viewModel.saveServer(server, secrets)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Nuevo servidor" else "Editar servidor") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancelar") }
                },
                actions = {
                    if (saving) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = ::save, enabled = canSave) { Text(if (existing == null) "Añadir" else "Guardar") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, placeholder = { Text("dev") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                host,
                { host = it },
                label = { Text("Host") },
                placeholder = { Text("192.168.1.10 o ejemplo.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Puerto") },
                    singleLine = true,
                    isError = port.isNotEmpty() && portValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
                OutlinedTextField(
                    username,
                    { username = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f),
                )
            }

            Text("Autenticación", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMethod.entries.forEachIndexed { index, method ->
                    SegmentedButton(
                        selected = authMethod == method,
                        onClick = { authMethod = method },
                        shape = SegmentedButtonDefaults.itemShape(index, AuthMethod.entries.size),
                    ) { Text(if (method == AuthMethod.KEY) "Clave SSH" else "Contraseña") }
                }
            }

            when (authMethod) {
                AuthMethod.KEY -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                keyText.isNullOrBlank() -> "Ninguna clave importada"
                                keyLabel.isNotBlank() -> "Clave: $keyLabel"
                                else -> "Clave importada"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (keyText.isNullOrBlank()) "Elegir archivo…" else "Reemplazar…")
                        }
                        OutlinedButton(onClick = { pasteDialog = true }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pegar clave")
                        }
                    }
                    keyError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    SecretField(passphrase, { passphrase = it; keyError = null }, "Passphrase (opcional)")
                    Text(
                        "Formatos: OpenSSH (Ed25519, ECDSA, RSA) o PEM. La clave y la passphrase se guardan cifradas con el Keystore de Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AuthMethod.PASSWORD -> {
                    SecretField(password, { password = it }, "Contraseña")
                    Text(
                        "La contraseña se guarda cifrada con el Keystore de Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (pasteDialog) {
        PasteKeyDialog(
            onConfirm = { text ->
                keyText = text
                keyLabel = "pegada"
                keyError = null
                pasteDialog = false
            },
            onDismiss = { pasteDialog = false },
        )
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Ocultar" else "Mostrar",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasteKeyDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pegar clave privada") },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                placeholder = { Text("Pega aquí tu clave privada (OpenSSH o PEM)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Usar clave") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Reads a picked key file as text. Fails with a user-facing message. */
private fun readKeyFile(context: Context, uri: Uri): Result<Pair<String, String>> = runCatching {
    val resolver = context.contentResolver
    val label = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "clave"
    val bytes = resolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_KEY_BYTES + 1) }
        ?: error("stream null")
    require(bytes.size <= MAX_KEY_BYTES) { "too big" }
    label to bytes.decodeToString()
}.recoverCatching { e ->
    Log.e("Argos/Form", "Reading key file $uri failed", e)
    throw IllegalStateException("No se pudo leer ese archivo como clave privada.")
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
