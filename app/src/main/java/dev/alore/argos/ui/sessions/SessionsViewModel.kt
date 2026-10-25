package dev.alore.argos.ui.sessions

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.alore.argos.ArgosApplication
import dev.alore.argos.ServerSecrets
import dev.alore.argos.data.SecretKind
import dev.alore.argos.model.SessionNameValidator
import dev.alore.argos.model.Server
import dev.alore.argos.model.TmuxSession
import dev.alore.argos.ssh.SshError
import dev.alore.argos.ssh.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerUi(val server: Server, val state: LoadState)

/**
 * Sessions of ALL configured servers (one [LoadState] per server) plus server management.
 * Activity-scoped: shared by the home screen and the server form.
 */
class SessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ArgosApplication).container
    private val store = container.serverStore

    private val states = MutableStateFlow<Map<String, LoadState>>(emptyMap())
    private val jobs = HashMap<String, Job>()

    val servers: StateFlow<List<ServerUi>> = combine(store.servers, states) { servers, states ->
        servers.map { ServerUi(it, states[it.id] ?: LoadState.Idle) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** One-shot messages for a snackbar (operation errors and confirmations). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        store.servers.value.forEach { load(it.id) }
    }

    /** Full connect: bootstrap tmux, then list. */
    fun load(serverId: String) {
        val server = store.server(serverId) ?: return
        launchFor(serverId) {
            SessionLoader.load(container.service(server)) { state -> setState(serverId, state) }
        }
    }

    fun loadAll() = store.servers.value.forEach { load(it.id) }

    /**
     * Re-lists without the bootstrap. [quiet] keeps the current list on screen until the result
     * arrives (used when coming back from a terminal, where attached flags change).
     */
    fun refresh(serverId: String, quiet: Boolean = false) {
        val server = store.server(serverId) ?: return
        // Never got a list (or failed/still bootstrapping): a manual refresh means a full retry,
        // a quiet one must not interrupt an ongoing bootstrap.
        if (states.value[serverId] !is LoadState.Loaded) {
            if (!quiet) load(serverId)
            return
        }
        launchFor(serverId) {
            if (!quiet) setState(serverId, LoadState.Loading)
            setState(serverId, SessionLoader.refresh(container.service(server)))
        }
    }

    fun refreshAllQuietly() = store.servers.value.forEach { refresh(it.id, quiet = true) }

    // MARK: - Session management (create/rename throw so their dialog shows the error inline)

    suspend fun createSession(serverId: String, rawName: String) {
        val server = store.server(serverId) ?: return
        val name = SessionNameValidator.validate(rawName)
        container.service(server).createSession(name)
        refresh(serverId)
    }

    suspend fun renameSession(serverId: String, session: TmuxSession, rawName: String) {
        val server = store.server(serverId) ?: return
        val newName = SessionNameValidator.validate(rawName)
        if (newName == session.name) return
        container.service(server).renameSession(session.name, newName)
        refresh(serverId)
    }

    fun kill(serverId: String, session: TmuxSession) {
        val server = store.server(serverId) ?: return
        viewModelScope.launch {
            try {
                container.service(server).killSession(session.name)
                _messages.send("Sesión '${session.name}' cerrada.")
                refresh(serverId)
            } catch (e: Exception) {
                if (e !is SshError) Log.e(TAG, "kill-session ${session.name} on ${server.endpoint} failed", e)
                _messages.send(e.userMessage)
            }
        }
    }

    // MARK: - Servers

    fun saveServer(server: Server, secrets: ServerSecrets) {
        container.saveServer(server, secrets)
        states.update { it - server.id }
        load(server.id)
    }

    /** Current secrets of a server, to prefill the edit form. */
    fun secretsOf(serverId: String): ServerSecrets {
        val secrets = container.secretStore
        return ServerSecrets(
            privateKey = secrets.get(serverId, SecretKind.PRIVATE_KEY),
            passphrase = secrets.get(serverId, SecretKind.PASSPHRASE),
            password = secrets.get(serverId, SecretKind.PASSWORD),
        )
    }

    fun deleteServer(server: Server) {
        jobs.remove(server.id)?.cancel()
        container.deleteServer(server)
        states.update { it - server.id }
        viewModelScope.launch { _messages.send("Servidor '${server.name}' eliminado.") }
    }

    fun forgetHostKey(server: Server) {
        container.knownHosts.forget(server.endpoint)
        viewModelScope.launch { _messages.send("Host key olvidada: se confiará en la próxima conexión.") }
        load(server.id)
    }

    private fun setState(serverId: String, state: LoadState) = states.update { it + (serverId to state) }

    private fun launchFor(serverId: String, block: suspend () -> Unit) {
        jobs[serverId]?.cancel()
        jobs[serverId] = viewModelScope.launch { block() }
    }

    private companion object {
        const val TAG = "Argos/Sessions"
    }
}
