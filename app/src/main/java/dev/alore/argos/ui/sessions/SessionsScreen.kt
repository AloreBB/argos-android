package dev.alore.argos.ui.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.alore.argos.model.Server
import dev.alore.argos.model.SessionGrouping
import dev.alore.argos.model.TmuxSession
import dev.alore.argos.ssh.SshError
import dev.alore.argos.ui.theme.Connected

/** A session plus the server it lives on (target of rename/kill dialogs). */
private data class SessionTarget(val server: Server, val session: TmuxSession)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenSession: (serverId: String, sessionName: String) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (serverId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    // Coming back from a terminal changes "attached" flags: re-list quietly.
    var hasResumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (hasResumedOnce) viewModel.refreshAllQuietly()
        hasResumedOnce = true
        onPauseOrDispose { }
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var createOn by remember { mutableStateOf<Server?>(null) }
    var pickServerForCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionTarget?>(null) }
    var killTarget by remember { mutableStateOf<SessionTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Buscar sesión") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Argos")
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cerrar búsqueda")
                        }
                    } else {
                        IconButton(onClick = { searching = true }, enabled = servers.isNotEmpty()) {
                            Icon(Icons.Outlined.Search, contentDescription = "Buscar sesión")
                        }
                        IconButton(onClick = onAddServer) {
                            Icon(Icons.Outlined.Dns, contentDescription = "Añadir servidor")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Ajustes")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (servers.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (servers.size == 1) createOn = servers.first().server else pickServerForCreate = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Nueva sesión") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (servers.isEmpty()) {
            NoServers(onAddServer, Modifier.padding(padding))
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = servers.any { it.state.isBusy },
            onRefresh = viewModel::loadAll,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                for (entry in servers) {
                    item(key = "header-${entry.server.id}") {
                        ServerHeader(
                            entry = entry,
                            onNewSession = { createOn = entry.server },
                            onRefresh = { viewModel.refresh(entry.server.id) },
                            onEdit = { onEditServer(entry.server.id) },
                            onForgetHostKey = { viewModel.forgetHostKey(entry.server) },
                            onDelete = { deleteTarget = entry.server },
                        )
                    }
                    serverSessions(
                        entry = entry,
                        query = query,
                        onOpen = { onOpenSession(entry.server.id, it.name) },
                        onRename = { renameTarget = SessionTarget(entry.server, it) },
                        onKill = { killTarget = SessionTarget(entry.server, it) },
                        onRetry = { viewModel.load(entry.server.id) },
                    )
                    item(key = "divider-${entry.server.id}") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }

    if (pickServerForCreate) {
        ServerPickerDialog(
            servers = servers.map { it.server },
            onPick = { pickServerForCreate = false; createOn = it },
            onDismiss = { pickServerForCreate = false },
        )
    }
    createOn?.let { server ->
        SessionNameDialog(
            mode = SessionNameMode.Create,
            serverName = server.name,
            onSubmit = { viewModel.createSession(server.id, it) },
            onDismiss = { createOn = null },
        )
    }
    renameTarget?.let { target ->
        SessionNameDialog(
            mode = SessionNameMode.Rename(target.session.name),
            serverName = target.server.name,
            onSubmit = { viewModel.renameSession(target.server.id, target.session, it) },
            onDismiss = { renameTarget = null },
        )
    }
    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("¿Matar la sesión '${target.session.name}'?") },
            text = { Text("Se cerrará y se perderá todo lo que corra en ella.") },
            confirmButton = {
                TextButton(onClick = { viewModel.kill(target.server.id, target.session); killTarget = null }) {
                    Text("Matar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancelar") } },
        )
    }
    deleteTarget?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("¿Eliminar el servidor '${server.name}'?") },
            text = { Text("Se borrará su configuración y sus credenciales de este teléfono. No afecta al servidor remoto.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteServer(server); deleteTarget = null }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun NoServers(onAddServer: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text("Sin servidores", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            "Añade un servidor SSH para ver y abrir sus sesiones tmux.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
        Button(onClick = onAddServer) { Text("Añadir servidor") }
    }
}

@Composable
private fun ServerHeader(
    entry: ServerUi,
    onNewSession: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onForgetHostKey: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerStatusIcon(entry.state)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.server.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${entry.server.username}@${entry.server.endpoint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Opciones de ${entry.server.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Nueva sesión") },
                    enabled = !entry.state.isBusy,
                    onClick = { menuOpen = false; onNewSession() },
                )
                DropdownMenuItem(text = { Text("Refrescar") }, onClick = { menuOpen = false; onRefresh() })
                DropdownMenuItem(text = { Text("Editar…") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text("Olvidar host key") }, onClick = { menuOpen = false; onForgetHostKey() })
                DropdownMenuItem(
                    text = { Text("Eliminar…", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun ServerStatusIcon(state: LoadState) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when {
            state.isBusy || state == LoadState.Idle -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            state is LoadState.Failed -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
            state is LoadState.Loaded -> Icon(Icons.Outlined.Dns, contentDescription = "Conectado", tint = Connected)
            else -> Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.serverSessions(
    entry: ServerUi,
    query: String,
    onOpen: (TmuxSession) -> Unit,
    onRename: (TmuxSession) -> Unit,
    onKill: (TmuxSession) -> Unit,
    onRetry: () -> Unit,
) {
    val key = entry.server.id
    when (val state = entry.state) {
        LoadState.Idle -> item(key = "$key-state") { StatusRow("Conectando…", busy = true) }
        LoadState.Verifying -> item(key = "$key-state") { StatusRow("Verificando tmux…", busy = true) }
        LoadState.Installing -> item(key = "$key-state") { StatusRow("Instalando tmux…", busy = true) }
        LoadState.Configuring -> item(key = "$key-state") { StatusRow("Configurando tmux…", busy = true) }
        LoadState.Loading -> item(key = "$key-state") { StatusRow("Listando sesiones…", busy = true) }
        LoadState.TmuxMissing -> item(key = "$key-state") {
            StatusRow(SshError.MANUAL_INSTALL_INSTRUCTION, icon = Icons.Outlined.Inventory2, action = "Reintentar", onAction = onRetry)
        }
        is LoadState.Failed -> item(key = "$key-state") {
            StatusRow(state.message, icon = Icons.Outlined.ErrorOutline, isError = true, action = "Reintentar", onAction = onRetry)
        }
        is LoadState.Loaded -> {
            val filtered = matching(state.sessions, query)
            when {
                state.sessions.isEmpty() -> item(key = "$key-state") { StatusRow("Sin sesiones activas", icon = Icons.Outlined.Bedtime) }
                filtered.isEmpty() -> item(key = "$key-state") { StatusRow("Sin coincidencias", icon = Icons.Outlined.SearchOff) }
                else -> {
                    val groups = SessionGrouping.groups(filtered)
                    // A lone "General" group needs no sub-header.
                    val showGroupHeaders = groups.size > 1 || groups.first().name != SessionGrouping.UNGROUPED
                    for (group in groups) {
                        if (showGroupHeaders) {
                            item(key = "$key-group-${group.name}") {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 52.dp, top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        items(group.sessions, key = { "$key-session-${it.name}" }) { session ->
                            SessionRow(
                                session = session,
                                title = if (showGroupHeaders) SessionGrouping.shortName(session.name) else session.name,
                                onOpen = { onOpen(session) },
                                onRename = { onRename(session) },
                                onKill = { onKill(session) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Substring match, case- and accent-insensitive. Empty query = everything. */
internal fun matching(sessions: List<TmuxSession>, query: String): List<TmuxSession> {
    val q = query.trim()
    if (q.isEmpty()) return sessions
    val normalizedQuery = q.foldForSearch()
    return sessions.filter { normalizedQuery in it.name.foldForSearch() }
}

private fun String.foldForSearch(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

@Composable
private fun StatusRow(
    text: String,
    busy: Boolean = false,
    icon: ImageVector? = null,
    isError: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            icon != null -> Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: TmuxSession,
    title: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
                onLongClickLabel = "Opciones de la sesión",
            ),
            leadingContent = {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = if (session.isAttached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = { Text(title, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle(session), maxLines = 1) },
            trailingContent = {
                Box(
                    Modifier.size(10.dp).background(
                        if (session.isAttached) Connected else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Abrir") }, onClick = { menuOpen = false; onOpen() })
            DropdownMenuItem(text = { Text("Renombrar…") }, onClick = { menuOpen = false; onRename() })
            DropdownMenuItem(
                text = { Text("Matar…", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onKill() },
            )
        }
    }
}

private fun subtitle(session: TmuxSession): String {
    val windows = "${session.windowCount} ${if (session.windowCount == 1) "ventana" else "ventanas"}"
    val age = DateUtils.getRelativeTimeSpanString(
        session.createdAtEpochSeconds * 1000,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    val attached = if (session.isAttached) " · en uso" else ""
    return "$windows · $age$attached"
}

@Composable
private fun ServerPickerDialog(servers: List<Server>, onPick: (Server) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿En qué servidor?") },
        text = {
            Column {
                for (server in servers) {
                    ListItem(
                        modifier = Modifier.clickable { onPick(server) },
                        headlineContent = { Text(server.name) },
                        supportingContent = { Text("${server.username}@${server.endpoint}") },
                        leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
