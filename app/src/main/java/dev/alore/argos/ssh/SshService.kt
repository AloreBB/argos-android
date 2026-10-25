package dev.alore.argos.ssh

import android.util.Log
import dev.alore.argos.model.AuthMethod
import dev.alore.argos.model.ShellQuoting
import dev.alore.argos.model.TmuxSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet

/** Connection parameters for one server, with its secrets already decrypted. */
data class SshConfig(
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: AuthMethod,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val password: String? = null,
) {
    val endpoint: String get() = "$host:$port"

    // Never leak secrets into logs.
    override fun toString(): String = "SshConfig($username@$endpoint, $authMethod)"
}

/** tmux operations the sessions list needs; [SshService] is the real one, tests use a fake. */
interface TmuxRemote {
    suspend fun listSessions(): List<TmuxSession>
    suspend fun isTmuxInstalled(): Boolean
    suspend fun canUseSudoNonInteractive(): Boolean
    suspend fun installTmuxWithApt()
    suspend fun tmuxConfigExists(): Boolean
    suspend fun writeDefaultTmuxConfig()
    suspend fun enableClipboardForwarding()
    suspend fun createSession(name: String)
    suspend fun renameSession(oldName: String, newName: String)
    suspend fun killSession(name: String)
}

/**
 * One shared SSH connection to a server (sshj, blocking I/O on [Dispatchers.IO]) plus every
 * remote operation Argos needs. The connection is created lazily and re-created when it drops;
 * [mutex] guarantees a single connect at a time. Commands, SFTP and the terminal PTY are separate
 * channels multiplexed over the same connection.
 */
class SshService(
    private val config: SshConfig,
    private val knownHosts: KnownHostsStore,
) : TmuxRemote {

    data class CommandResult(val stdout: String, val stderr: String, val exitCode: Int)

    private val mutex = Mutex()
    private var client: SSHClient? = null

    // MARK: - Sessions

    /** "no server running" / "no sessions" are an EMPTY list, not an error. */
    override suspend fun listSessions(): List<TmuxSession> {
        val result = capture(TmuxOutput.LIST_SESSIONS_COMMAND)
        if (result.exitCode != 0) {
            if (TmuxOutput.indicatesNoTmuxServer(result.stdout, result.stderr)) return emptyList()
            if (TmuxOutput.indicatesTmuxNotInstalled(result.stdout, result.stderr, result.exitCode)) {
                throw SshError.TmuxNotInstalled()
            }
            throw SshError.CommandFailed(
                result.exitCode,
                result.stderr.trim().ifEmpty { "tmux list-sessions no devolvió detalles." },
            )
        }
        return TmuxSession.parseList(result.stdout)
    }

    override suspend fun createSession(name: String) =
        runManagementCommand("tmux new-session -d -s ${ShellQuoting.singleQuoted(name)}")

    override suspend fun renameSession(oldName: String, newName: String) = runManagementCommand(
        "tmux rename-session -t ${ShellQuoting.singleQuoted(oldName)} ${ShellQuoting.singleQuoted(newName)}",
    )

    /** Destructive: everything running in the session is lost. */
    override suspend fun killSession(name: String) =
        runManagementCommand("tmux kill-session -t ${ShellQuoting.singleQuoted(name)}")

    /** Throws [SshError.CommandFailed] with tmux's stderr (duplicate name, missing session…). */
    private suspend fun runManagementCommand(command: String) {
        val result = capture(command)
        if (result.exitCode != 0) {
            throw SshError.CommandFailed(result.exitCode, result.stderr.trim().ifEmpty { "tmux no devolvió detalles." })
        }
    }

    // MARK: - tmux bootstrap (Ubuntu/Debian). Idempotent and non-interactive: never asks for sudo's password.

    override suspend fun isTmuxInstalled(): Boolean = capture("command -v tmux").exitCode == 0

    /** `sudo -n` fails instead of prompting, so it is safe on a channel without TTY. */
    override suspend fun canUseSudoNonInteractive(): Boolean = capture("sudo -n true").exitCode == 0

    /** Precondition: [canUseSudoNonInteractive]. Verified independently of apt's exit code. */
    override suspend fun installTmuxWithApt() {
        val result = capture(TmuxOutput.INSTALL_WITH_APT_COMMAND)
        if (!isTmuxInstalled()) {
            throw SshError.InstallFailed(result.stderr.trim().ifEmpty { "apt terminó con código ${result.exitCode}." })
        }
    }

    override suspend fun tmuxConfigExists(): Boolean = capture("test -f \"\$HOME/.tmux.conf\"").exitCode == 0

    override suspend fun writeDefaultTmuxConfig() {
        val result = capture(TmuxOutput.WRITE_DEFAULT_CONFIG_COMMAND)
        if (result.exitCode != 0) {
            throw SshError.ConfigFailed(result.stderr.trim().ifEmpty { "Código de salida ${result.exitCode}." })
        }
    }

    /** Best-effort: must never block the connection flow. */
    override suspend fun enableClipboardForwarding() {
        try {
            capture(TmuxOutput.ENABLE_CLIPBOARD_COMMAND)
        } catch (e: Exception) {
            Log.w(TAG, "enableClipboardForwarding failed on ${config.endpoint} (ignored)", e)
        }
    }

    // MARK: - File upload (SFTP)

    /** Uploads to `/tmp/argos-pasted-<ts>.<ext>` and returns the absolute remote path. */
    suspend fun uploadPastedImage(input: InputStream, extension: String): String =
        upload(input, RemoteFiles.pastedImagePath(extension))

    /** Uploads to `/tmp/argos-<ts>-<sanitized name>` and returns the absolute remote path. */
    suspend fun uploadFile(input: InputStream, originalName: String): String =
        upload(input, RemoteFiles.uploadedFilePath(originalName))

    /** `internal` for tests (fixed paths); the app goes through the two helpers above. */
    internal suspend fun upload(input: InputStream, remotePath: String): String {
        val client = connectedClient()
        withContext(Dispatchers.IO) {
            try {
                client.newSFTPClient().use { sftp ->
                    // EXCL: never follow/overwrite something pre-created in the shared /tmp
                    // (e.g. a symlink); 0600: other users of the server can't read the upload.
                    val privateFile = FileAttributes.Builder().withPermissions(PRIVATE_FILE_MODE).build()
                    sftp.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), privateFile).use { file ->
                        file.RemoteFileOutputStream().use { out -> input.copyTo(out) }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "SFTP upload to $remotePath on ${config.endpoint} failed", e)
                throw SshError.UploadFailed(e)
            }
        }
        Log.i(TAG, "Uploaded via SFTP to $remotePath on ${config.endpoint}")
        return remotePath
    }

    // MARK: - Live terminal

    /**
     * Opens an interactive PTY running `tmux attach` for [sessionName]. Closing the returned
     * [RemoteTerminal] closes the channel: tmux gets SIGHUP and DETACHES this client; the session
     * keeps living on the server.
     */
    suspend fun openTerminal(sessionName: String, columns: Int, rows: Int): RemoteTerminal {
        val client = connectedClient()
        return withContext(Dispatchers.IO) {
            val session = client.startSession()
            try {
                session.allocatePTY(
                    "xterm-256color",
                    columns.coerceAtLeast(1),
                    rows.coerceAtLeast(1),
                    0,
                    0,
                    mapOf(PTYMode.ECHO to 1, PTYMode.ICRNL to 1),
                )
                // `-u` forces UTF-8: an exec channel often has no UTF-8 locale and tmux would
                // otherwise degrade box drawing/emoji to '_'.
                val command = session.exec("exec tmux -u attach -t ${ShellQuoting.singleQuoted(sessionName)}")
                RemoteTerminal(session, command)
            } catch (e: Exception) {
                session.closeQuietly()
                throw e.toSshError()
            }
        }
    }

    // MARK: - Connection

    /** Closes the shared connection (open terminals on it end too). */
    suspend fun disconnect() {
        val toClose = mutex.withLock { client.also { client = null } } ?: return
        withContext(Dispatchers.IO) {
            try {
                toClose.disconnect()
            } catch (e: IOException) {
                Log.d(TAG, "disconnect from ${config.endpoint}: ${e.message}")
            }
        }
    }

    private suspend fun connectedClient(): SSHClient = mutex.withLock {
        client?.takeIf { it.isConnected && it.isAuthenticated }?.let { return@withLock it }
        client?.closeQuietly()
        client = null
        withContext(Dispatchers.IO) { connect() }.also { client = it }
    }

    private fun connect(): SSHClient {
        // Load the key BEFORE opening a socket: a bad key/passphrase is a local, precise error.
        val keyProvider = if (config.authMethod == AuthMethod.KEY) loadKey(config) else null

        val sshConfig = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val ssh = SSHClient(sshConfig)
        val verifier = TofuHostKeyVerifier(knownHosts)
        ssh.addHostKeyVerifier(verifier)
        ssh.connectTimeout = CONNECT_TIMEOUT_MS
        try {
            ssh.connect(config.host, config.port)
            // Mobile networks silently drop idle TCP; keep-alives also detect a dead link (~30 s).
            ssh.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_SECONDS
            when (config.authMethod) {
                AuthMethod.KEY -> ssh.authPublickey(config.username, keyProvider)
                AuthMethod.PASSWORD -> {
                    val password = config.password.orEmpty()
                    val finder = staticPassword(password)
                    // Some servers only offer keyboard-interactive (PAM) for passwords.
                    ssh.auth(
                        config.username,
                        AuthPassword(finder),
                        AuthKeyboardInteractive(PasswordResponseProvider(finder)),
                    )
                }
            }
            Log.i(TAG, "SSH connected to ${config.endpoint}")
            return ssh
        } catch (e: Exception) {
            ssh.closeQuietly()
            verifier.mismatch?.let { throw it }
            Log.w(TAG, "SSH connect to ${config.endpoint} failed", e)
            throw e.toSshError()
        }
    }

    /** Runs [command] on a fresh exec channel, capturing stdout, stderr and the exit code. */
    suspend fun capture(command: String): CommandResult {
        val client = connectedClient()
        return withContext(Dispatchers.IO) {
            try {
                client.startSession().use { session ->
                    val cmd = session.exec(command)
                    coroutineScope {
                        // Drain both streams concurrently: a full stderr window would stall stdout.
                        val stderr = async { cmd.errorStream.readBytes().decodeToString() }
                        val stdout = cmd.inputStream.readBytes().decodeToString()
                        val err = stderr.await()
                        cmd.join()
                        CommandResult(stdout, err, cmd.exitStatus ?: -1)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Command failed on ${config.endpoint}", e)
                throw e.toSshError()
            }
        }
    }

    private fun Throwable.toSshError(): SshError = when (this) {
        is SshError -> this
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
            SshError.HostUnreachable(config.endpoint, this)
        is UserAuthException -> SshError.AuthFailed(this)
        is TransportException ->
            if (disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) SshError.ConnectionFailed(this)
            else (cause as? SocketTimeoutException)?.let { SshError.HostUnreachable(config.endpoint, it) }
                ?: SshError.ConnectionFailed(this)
        else -> SshError.ConnectionFailed(this)
    }

    companion object {
        private const val TAG = "Argos/SSH"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val KEEP_ALIVE_SECONDS = 10
        private const val PRIVATE_FILE_MODE = 0x180 // 0600

        /**
         * Parses the private key eagerly (sshj parses lazily) so a wrong format or passphrase is
         * reported as [SshError.KeyParseFailed]. Also used by the server form to validate input.
         */
        fun loadKey(config: SshConfig): KeyProvider {
            val keyText = config.privateKey?.takeIf { it.isNotBlank() } ?: throw SshError.KeyMissing()
            return try {
                val finder = config.passphrase?.takeIf { it.isNotEmpty() }?.let(::staticPassword)
                SSHClient(DefaultConfig()).loadKeys(keyText.trim() + "\n", null, finder).also {
                    it.private
                    it.public
                }
            } catch (e: Exception) {
                throw SshError.KeyParseFailed(e)
            }
        }

        private fun staticPassword(secret: String) = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = secret.toCharArray()
            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }

        private fun Closeable.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // Already closing/closed: nothing actionable.
            }
        }
    }
}

/** A live PTY channel running `tmux attach`. */
class RemoteTerminal internal constructor(
    private val session: Session,
    private val command: Session.Command,
) : Closeable {
    /** Remote output (stdout; with a PTY the server merges stderr into it). */
    val output: InputStream get() = command.inputStream

    /** Blocking write to the PTY's stdin. Call from a background thread. */
    fun write(bytes: ByteArray) {
        command.outputStream.write(bytes)
        command.outputStream.flush()
    }

    /** Sends an SSH window-change. Call from a background thread. */
    fun resize(columns: Int, rows: Int) {
        // sshj only declares window-change on Session.Shell, but exec() returns the same
        // SessionChannel, which implements it for any PTY channel.
        if (columns > 0 && rows > 0) (command as SessionChannel).changeWindowDimensions(columns, rows, 0, 0)
    }

    override fun close() {
        try {
            command.close()
        } catch (_: IOException) {
            // Channel already gone (EOF/disconnect): detach already happened.
        }
        try {
            session.close()
        } catch (_: IOException) {
            // Same as above.
        }
    }
}
