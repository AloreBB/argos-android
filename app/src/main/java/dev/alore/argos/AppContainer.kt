package dev.alore.argos

import android.content.Context
import android.util.Log
import dev.alore.argos.data.SecretKind
import dev.alore.argos.data.SecretStore
import dev.alore.argos.data.ServerStore
import dev.alore.argos.data.TerminalSettings
import dev.alore.argos.model.AuthMethod
import dev.alore.argos.model.Server
import dev.alore.argos.ssh.KnownHostsStore
import dev.alore.argos.ssh.SshConfig
import dev.alore.argos.ssh.SshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Secrets typed in the server form; persisted encrypted, never in [Server]. */
data class ServerSecrets(val privateKey: String?, val passphrase: String?, val password: String?) {
    override fun toString() = "ServerSecrets(***)"
}

/**
 * App-wide singletons (manual DI). One [SshService] per server is shared by the sessions list and
 * the terminal, like Argos' one-VM-per-server: both use the same SSH connection.
 */
class AppContainer(context: Context) {
    val serverStore = ServerStore(File(context.filesDir, "servers.json"))
    val secretStore = SecretStore(context)
    val knownHosts = KnownHostsStore(File(context.filesDir, "known_hosts.json"))
    val terminalSettings = TerminalSettings(context)

    /** Outlives screens: used to close connections in the background. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services = HashMap<String, SshService>()

    @Synchronized
    fun service(server: Server): SshService = services.getOrPut(server.id) { SshService(configFor(server), knownHosts) }

    fun configFor(server: Server): SshConfig = when (server.authMethod) {
        AuthMethod.KEY -> SshConfig(
            host = server.host,
            port = server.port,
            username = server.username,
            authMethod = AuthMethod.KEY,
            privateKey = secretStore.get(server.id, SecretKind.PRIVATE_KEY),
            passphrase = secretStore.get(server.id, SecretKind.PASSPHRASE),
        )
        AuthMethod.PASSWORD -> SshConfig(
            host = server.host,
            port = server.port,
            username = server.username,
            authMethod = AuthMethod.PASSWORD,
            password = secretStore.get(server.id, SecretKind.PASSWORD),
        )
    }

    /** Persists the server and its secrets, and drops its cached connection (credentials may have changed). */
    fun saveServer(server: Server, secrets: ServerSecrets) {
        when (server.authMethod) {
            AuthMethod.KEY -> {
                secretStore.put(server.id, SecretKind.PRIVATE_KEY, secrets.privateKey)
                secretStore.put(server.id, SecretKind.PASSPHRASE, secrets.passphrase)
                secretStore.put(server.id, SecretKind.PASSWORD, null)
            }
            AuthMethod.PASSWORD -> {
                secretStore.put(server.id, SecretKind.PASSWORD, secrets.password)
                secretStore.put(server.id, SecretKind.PRIVATE_KEY, null)
                secretStore.put(server.id, SecretKind.PASSPHRASE, null)
            }
        }
        invalidate(server.id)
        serverStore.upsert(server)
    }

    /** Removes the server, its secrets and its connection. Does not touch the remote server. */
    fun deleteServer(server: Server) {
        invalidate(server.id)
        serverStore.remove(server.id)
        secretStore.deleteAll(server.id)
    }

    private fun invalidate(serverId: String) {
        val old = synchronized(this) { services.remove(serverId) } ?: return
        appScope.launch {
            try {
                old.disconnect()
            } catch (e: Exception) {
                Log.w("Argos/App", "Closing old connection for $serverId failed", e)
            }
        }
    }
}
