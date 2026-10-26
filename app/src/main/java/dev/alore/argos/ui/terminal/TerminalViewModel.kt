package dev.alore.argos.ui.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import dev.alore.argos.ArgosApplication
import dev.alore.argos.ssh.RemoteTerminal
import dev.alore.argos.ssh.SshError
import dev.alore.argos.ssh.SshService
import dev.alore.argos.ssh.userMessage
import dev.alore.argos.ui.TerminalRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.pow

sealed interface TerminalStatus {
    data object Connecting : TerminalStatus
    data object Connected : TerminalStatus
    /** Clean detach (EOF): the tmux session is still alive on the server. */
    data object Ended : TerminalStatus
    data class Failed(val message: String) : TerminalStatus
}

/**
 * Live terminal attached to one tmux session. Owns the Termux [TerminalSession] (so it survives
 * configuration changes) and the PTY channel; the screen only attaches a [TerminalView] to it.
 *
 * Lifecycle = detach, never kill: leaving the screen closes the channel and tmux detaches this
 * client. Network failures reconnect automatically with exponential backoff (1,2,4,8,16 s, up to
 * [MAX_AUTO_RECONNECTS]); a clean detach does not.
 */
class TerminalViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val container = (application as ArgosApplication).container
    private val route = savedStateHandle.toRoute<TerminalRoute>()
    val sessionName: String = route.sessionName

    private val _status = MutableStateFlow<TerminalStatus>(TerminalStatus.Connecting)
    val status: StateFlow<TerminalStatus> = _status.asStateFlow()

    private val _session = MutableStateFlow<TerminalSession?>(null)
    /** Termux session of the current attempt; a new one per (re)connection. */
    val session: StateFlow<TerminalSession?> = _session.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    /** 1-based attempt number while an automatic reconnection is pending, else null. */
    private val _autoReconnectAttempt = MutableStateFlow<Int?>(null)
    val autoReconnectAttempt: StateFlow<Int?> = _autoReconnectAttempt.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** The view currently showing [session]; set/cleared by the screen. */
    var attachedView: TerminalView? = null

    private var attemptJob: Job? = null
    private var reconnectJob: Job? = null
    private var autoReconnectCount = 0

    private val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Manual "Reconectar": resets the automatic retry budget. */
    fun reconnect() {
        autoReconnectCount = 0
        reconnectJob?.cancel()
        _autoReconnectAttempt.value = null
        startAttempt()
    }

    private fun startAttempt() {
        attemptJob?.cancel()
        _session.value?.finish(null)
        _status.value = TerminalStatus.Connecting

        val events = Channel<PtyEvent>(Channel.UNLIMITED)
        val firstSize = CompletableDeferred<PtyEvent.Resize>()
        val transport = object : TerminalSession.Transport {
            // Called on the main thread by the view: enqueue only, never block.
            override fun write(data: ByteArray, offset: Int, count: Int) {
                events.trySend(PtyEvent.Input(data.copyOfRange(offset, offset + count)))
            }

            override fun resize(columns: Int, rows: Int) {
                val resize = PtyEvent.Resize(columns, rows)
                if (!firstSize.complete(resize)) events.trySend(resize)
            }
        }
        val session = TerminalSession(TRANSCRIPT_ROWS, sessionClient, transport)
        _session.value = session
        attemptJob = viewModelScope.launch { runAttempt(session, events, firstSize) }
    }

    private suspend fun runAttempt(
        session: TerminalSession,
        events: Channel<PtyEvent>,
        firstSize: CompletableDeferred<PtyEvent.Resize>,
    ) {
        val server = container.serverStore.server(route.serverId)
        if (server == null) {
            _status.value = TerminalStatus.Failed("El servidor ya no existe.")
            return
        }
        val service: SshService = container.service(server)
        // The PTY is opened with the real size of the view (known once it is laid out).
        val size = firstSize.await()

        var remote: RemoteTerminal? = null
        try {
            remote = service.openTerminal(sessionName, size.columns, size.rows)
            val openRemote = remote
            val readError = coroutineScope {
                val writer = launch(Dispatchers.IO) {
                    for (event in events) {
                        when (event) {
                            is PtyEvent.Input -> openRemote.write(event.bytes)
                            is PtyEvent.Resize -> openRemote.resize(event.columns, event.rows)
                        }
                    }
                }
                val firstOutput = CompletableDeferred<Unit>()
                val reveal = launch {
                    // Reveal after a short margin so tmux can repaint (no black flash on attach).
                    firstOutput.await()
                    delay(REVEAL_DELAY_MS)
                    if (_status.value == TerminalStatus.Connecting) {
                        _status.value = TerminalStatus.Connected
                        autoReconnectCount = 0
                        _autoReconnectAttempt.value = null
                    }
                }

                // Blocking reads live on a plain thread: cancelling this coroutine closes the
                // channel (finally below), which unblocks the read.
                val readerDone = CompletableDeferred<Throwable?>()
                thread(name = "argos-pty-reader", isDaemon = true) {
                    val buffer = ByteArray(8192)
                    try {
                        while (true) {
                            val read = openRemote.output.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            if (!session.appendRemoteOutput(buffer, 0, read)) break
                            firstOutput.complete(Unit)
                        }
                        readerDone.complete(null)
                    } catch (e: Exception) {
                        readerDone.complete(e)
                    }
                }

                readerDone.await().also {
                    writer.cancel()
                    reveal.cancel()
                }
            }
            if (readError == null) {
                session.finish(null)
                _status.value = TerminalStatus.Ended
            } else {
                Log.w(TAG, "PTY read failed for $sessionName on ${server.endpoint}", readError)
                fail(session, SshError.ConnectionFailed(readError))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is SshError) Log.e(TAG, "Opening terminal $sessionName on ${server.endpoint} failed", e)
            fail(session, e)
        } finally {
            remote?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
        }
    }

    private fun fail(session: TerminalSession, error: Throwable) {
        session.finish(null)
        _status.value = TerminalStatus.Failed(error.userMessage)
        // Only network-level failures are worth retrying; a bad key, rejected credentials or a
        // changed host key would just fail again.
        val transient = error is SshError.ConnectionFailed || error is SshError.HostUnreachable || error !is SshError
        if (!transient || autoReconnectCount >= MAX_AUTO_RECONNECTS) {
            _autoReconnectAttempt.value = null
            return
        }
        scheduleAutoReconnect()
    }

    private fun scheduleAutoReconnect() {
        reconnectJob?.cancel()
        val delaySeconds = min(2.0.pow(autoReconnectCount), 16.0)
        _autoReconnectAttempt.value = autoReconnectCount + 1
        reconnectJob = viewModelScope.launch {
            delay((delaySeconds * 1000).toLong())
            autoReconnectCount += 1
            startAttempt()
        }
    }

    // MARK: - Clipboard & uploads

    /**
     * "Pegar": an image in the clipboard is uploaded via SFTP and its remote path typed (so remote
     * apps like Claude Code can read it); otherwise the text is pasted (bracketed-paste aware).
     */
    fun pasteFromClipboard() {
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return
        val uri = item.uri
        val mime = uri?.let { getApplication<Application>().contentResolver.getType(it) }
        if (uri != null && mime?.startsWith("image/") == true) {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            upload(listOf(uri)) { service, input, _ -> service.uploadPastedImage(input, extension) }
            return
        }
        val text = item.coerceToText(getApplication()).toString()
        if (text.isNotEmpty()) _session.value?.emulator?.paste(text)
    }

    /** Uploads picked files to /tmp and types their remote paths, space-separated. */
    fun uploadFiles(uris: List<Uri>) = upload(uris) { service, input, name -> service.uploadFile(input, name) }

    private fun upload(uris: List<Uri>, send: suspend (SshService, java.io.InputStream, String) -> String) {
        if (uris.isEmpty() || _isUploading.value) return
        val server = container.serverStore.server(route.serverId) ?: return
        val service = container.service(server)
        val resolver = getApplication<Application>().contentResolver
        _isUploading.value = true
        viewModelScope.launch {
            try {
                for (uri in uris) {
                    val name = withContext(Dispatchers.IO) { displayName(uri) }
                    try {
                        val path = withContext(Dispatchers.IO) {
                            val input = resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
                            input.use { send(service, it, name) }
                        }
                        _session.value?.write("$path ")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Upload of $name to ${server.endpoint} failed", e)
                        val reason = if (e is SshError) e.userMessage else "No se pudo leer el archivo."
                        _messages.send("No se pudo subir $name. $reason")
                    }
                }
            } finally {
                _isUploading.value = false
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "archivo"
    }

    fun copyLink(url: String) {
        copyToClipboard(url)
        _messages.trySend("Enlace copiado")
    }

    private fun copyToClipboard(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Argos", text))
    }

    // MARK: - Termux session callbacks (main thread)

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            attachedView?.takeIf { it.currentSession === changedSession }?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            attachedView?.takeIf { it.currentSession === finishedSession }?.onScreenUpdated()
        }

        /** "Copiar" from the local selection menu. */
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (!text.isNullOrEmpty()) copyToClipboard(text)
        }

        /**
         * OSC 52: the server (tmux, Claude Code's "c to copy"…) sets the clipboard. Useful, but
         * remote-controlled: never silent (always announced) and size-capped, so output from an
         * untrusted file can't quietly swap what the user is about to paste elsewhere.
         */
        override fun onRemoteClipboardCopy(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            if (text.length > MAX_REMOTE_CLIPBOARD_CHARS) {
                Log.w(TAG, "Ignored OSC 52 copy of ${text.length} chars from $sessionName")
                _messages.trySend("Se ignoró una copia al portapapeles demasiado grande enviada por el servidor.")
                return
            }
            copyToClipboard(text)
            _messages.trySend("El servidor copió ${text.length} caracteres al portapapeles.")
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) = pasteFromClipboard()

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            attachedView?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message.orEmpty(), e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }

    // Must stay below every property it uses (sessionClient, clipboard…): Kotlin runs
    // initializers in declaration order.
    init {
        startAttempt()
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        attemptJob?.cancel()
        _session.value?.finish(null)
        attachedView = null
    }

    private sealed interface PtyEvent {
        class Input(val bytes: ByteArray) : PtyEvent
        data class Resize(val columns: Int, val rows: Int) : PtyEvent
    }

    companion object {
        const val MAX_AUTO_RECONNECTS = 5
        private const val TAG = "Argos/Terminal"
        private const val REVEAL_DELAY_MS = 350L
        private const val MAX_REMOTE_CLIPBOARD_CHARS = 100_000
        /** Local scrollback; tmux keeps its own (history-limit 50000). */
        private const val TRANSCRIPT_ROWS = 2000
    }
}
