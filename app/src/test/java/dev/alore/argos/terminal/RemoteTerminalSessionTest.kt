package dev.alore.argos.terminal

import android.os.Looper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** The vendored Termux TerminalSession, rewired from a local process to a remote byte stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteTerminalSessionTest {
    private val sent = ByteArrayOutputStream()
    private val sizes = mutableListOf<Pair<Int, Int>>()
    private var copied: String? = null
    private var finished = false

    private val transport = object : TerminalSession.Transport {
        override fun write(data: ByteArray, offset: Int, count: Int) = sent.write(data, offset, count)
        override fun resize(columns: Int, rows: Int) { sizes += columns to rows }
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) { finished = true }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { copied = text }
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun TerminalSession.screenText() = emulator.screen.transcriptText

    @Test
    fun `output arriving before the first size is shown once the emulator exists`() {
        val session = TerminalSession(100, client, transport)
        session.appendRemoteOutput("early\r\n".toByteArray(), 0, 7)
        idle()
        session.updateSize(40, 10, 8, 16)
        idle()
        assertTrue(session.screenText().contains("early"))
        assertEquals(listOf(40 to 10), sizes)
    }

    @Test
    fun `remote output renders, typing and resizes go to the transport`() {
        val session = TerminalSession(100, client, transport)
        session.updateSize(40, 10, 8, 16)
        val bytes = "hola \u001b[1mmundo\u001b[0m ñ\r\n".toByteArray()
        session.appendRemoteOutput(bytes, 0, bytes.size)
        idle()
        assertTrue(session.screenText(), session.screenText().contains("hola mundo ñ"))

        session.writeCodePoint(false, 'ü'.code)
        session.write("ls\r")
        assertEquals("üls\r", sent.toString(Charsets.UTF_8))

        session.updateSize(80, 24, 8, 16)
        assertEquals(listOf(40 to 10, 80 to 24), sizes)
        assertEquals(80, session.emulator.columns)
    }

    @Test
    fun `OSC 52 copies to the clipboard callback`() {
        val session = TerminalSession(100, client, transport)
        session.updateSize(40, 10, 8, 16)
        val payload = java.util.Base64.getEncoder().encodeToString("copiado ✓".toByteArray())
        val osc = "\u001b]52;c;$payload\u0007".toByteArray()
        session.appendRemoteOutput(osc, 0, osc.size)
        idle()
        assertEquals("copiado ✓", copied)
    }

    @Test
    fun `finish drains pending output, prints the message and stops input`() {
        val session = TerminalSession(100, client, transport)
        session.updateSize(40, 10, 8, 16)
        session.appendRemoteOutput("last\r\n".toByteArray(), 0, 6)
        session.finish("[fin]")
        idle()
        assertTrue(finished)
        assertTrue(session.screenText().contains("last"))
        assertTrue(session.screenText().contains("[fin]"))
        assertFalse(session.isRunning)
        assertFalse(session.appendRemoteOutput("x".toByteArray(), 0, 1))
        session.write("ignored")
        assertEquals("", sent.toString(Charsets.UTF_8))
    }
}
