package dev.alore.argos.ssh

import dev.alore.argos.ArgosApplication
import dev.alore.argos.model.AuthMethod
import dev.alore.argos.ui.sessions.LoadState
import dev.alore.argos.ui.sessions.SessionLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runners.MethodSorters
import java.io.File
import kotlin.concurrent.thread

/**
 * End-to-end check of [SshService] against a real sshd (see `it/run-integration.sh`).
 * Skipped unless ARGOS_IT_PORT is set. Methods run in name order: the bootstrap tests must run
 * before tmux gets installed on the container.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SshServiceIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val knownHosts by lazy { KnownHostsStore(tmp.root.resolve("known_hosts.json")) }

    private fun keyConfig(user: String = "argos", key: String = "id_ed25519", passphrase: String? = null) = SshConfig(
        host = HOST, port = port, username = user, authMethod = AuthMethod.KEY,
        privateKey = File(keysDir, key).readText(), passphrase = passphrase,
    )

    private fun service(config: SshConfig = keyConfig(), store: KnownHostsStore = knownHosts) = SshService(config, store)

    @Test
    fun `a1 user without sudo and without tmux gets the manual instruction`() = runBlocking<Unit> {
        val states = mutableListOf<LoadState>()
        val final = SessionLoader.load(service(keyConfig(user = "bare"))) { states += it }
        assertEquals(LoadState.TmuxMissing, final)
    }

    @Test
    fun `a2 bootstrap installs tmux with sudo and writes the config`() = runBlocking<Unit> {
        val svc = service()
        val states = mutableListOf<LoadState>()
        val final = SessionLoader.load(svc) { states += it }
        assertEquals(LoadState.Loaded(emptyList()), final)
        assertTrue(states.toString(), LoadState.Installing in states && LoadState.Configuring in states)
        assertTrue(svc.tmuxConfigExists())
        // Idempotent: a second load neither reinstalls nor rewrites.
        val again = mutableListOf<LoadState>()
        SessionLoader.load(svc) { again += it }
        assertFalse(again.toString(), LoadState.Installing in again || LoadState.Configuring in again)
    }

    @Test
    fun `b create rename kill sessions`() = runBlocking<Unit> {
        val svc = service()
        svc.createSession("grupo/uno")
        svc.createSession("it's quoted")
        assertEquals(setOf("grupo/uno", "it's quoted"), svc.listSessions().map { it.name }.toSet())

        val duplicate = runCatching { svc.createSession("grupo/uno") }.exceptionOrNull()
        assertTrue(duplicate.toString(), duplicate is SshError.CommandFailed && "duplicate" in duplicate.message!!)

        svc.renameSession("grupo/uno", "grupo/dos")
        assertTrue(svc.listSessions().any { it.name == "grupo/dos" })

        svc.killSession("grupo/dos")
        svc.killSession("it's quoted")
        assertTrue(svc.listSessions().isEmpty())
        svc.disconnect()
    }

    @Test
    fun `c password auth and rejected credentials`() = runBlocking<Unit> {
        val ok = SshConfig(HOST, port, "argos", AuthMethod.PASSWORD, password = System.getenv("ARGOS_IT_PASSWORD"))
        assertTrue(service(ok).isTmuxInstalled())

        val bad = ok.copy(password = ok.password + "-wrong")
        val error = runCatching { service(bad).isTmuxInstalled() }.exceptionOrNull()
        assertTrue(error.toString(), error is SshError.AuthFailed)
    }

    @Test
    fun `d encrypted key needs the right passphrase`() = runBlocking<Unit> {
        val passphrase = System.getenv("ARGOS_IT_PASSPHRASE")
        assertTrue(service(keyConfig(key = "id_ed25519_enc", passphrase = passphrase)).isTmuxInstalled())
        assertThrows(SshError.KeyParseFailed::class.java) {
            SshService.loadKey(keyConfig(key = "id_ed25519_enc", passphrase = "$passphrase-wrong"))
        }
        assertThrows(SshError.KeyParseFailed::class.java) { SshService.loadKey(keyConfig().copy(privateKey = "not a key")) }
    }

    @Test
    fun `e host key TOFU detects a changed key and forget re-trusts`() = runBlocking<Unit> {
        val store = knownHosts
        service(store = store).isTmuxInstalled()
        val trusted = store.get("$HOST:$port")!!
        assertTrue(trusted.fingerprint.startsWith("SHA256:"))

        // Simulate a MitM / reinstalled server: the stored fingerprint no longer matches.
        store.forget("$HOST:$port")
        store.verifyOrTrust("$HOST:$port", trusted.copy(fingerprint = "SHA256:somethingelse"))
        val error = runCatching { service(store = store).isTmuxInstalled() }.exceptionOrNull()
        assertTrue(error.toString(), error is SshError.HostKeyMismatch)

        store.forget("$HOST:$port")
        assertTrue(service(store = store).isTmuxInstalled())
        assertEquals(trusted, store.get("$HOST:$port"))
    }

    @Test
    fun `f unreachable port is a clear error`() = runBlocking<Unit> {
        val error = runCatching { service(keyConfig().copy(port = 1)).isTmuxInstalled() }.exceptionOrNull()
        assertTrue(error.toString(), error is SshError.HostUnreachable)
    }

    @Test
    fun `g sftp upload lands in tmp with a safe name`() = runBlocking<Unit> {
        val svc = service()
        val content = "hola Argos ✓\n"
        val path = svc.uploadFile(content.byteInputStream(), "mi archivo (1).txt")
        assertTrue(path, path.matches(Regex("/tmp/argos-\\d+-[0-9a-f]{8}-mi_archivo__1_.txt")))
        assertEquals(content, svc.capture("cat $path").stdout)
        assertEquals("private to the user", "600", svc.capture("stat -c %a $path").stdout.trim())

        // A pre-existing symlink at the path must never be followed.
        val victim = "/home/argos/victim.txt"
        svc.capture("echo intact > $victim && ln -sf $victim /tmp/argos-trap")
        val trap = runCatching { svc.upload("pwned".byteInputStream(), "/tmp/argos-trap") }
        assertTrue("upload onto an existing path must fail", trap.isFailure)
        assertEquals("intact", svc.capture("cat $victim").stdout.trim())

        val image = svc.uploadPastedImage(byteArrayOf(1, 2, 3).inputStream(), "png")
        assertTrue(image, image.matches(Regex("/tmp/argos-pasted-\\d+-[0-9a-f]{8}.png")))
        assertEquals("3", svc.capture("stat -c %s $image").stdout.trim())
    }

    @Test
    fun `h live terminal types, resizes and detaches without killing`() = runBlocking<Unit> {
        val svc = service()
        svc.createSession("term")
        val terminal = svc.openTerminal("term", 80, 24)
        val output = StringBuffer()
        val reader = thread(isDaemon = true) {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val n = terminal.output.read(buffer)
                    if (n < 0) break
                    output.append(String(buffer, 0, n, Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // Closed by the test.
            }
        }

        waitFor("attached client") { svc.capture("tmux list-clients -t term -F x").stdout.isNotBlank() }
        terminal.write("echo ARGOS_\$((40+2)) ñ✓\r".toByteArray())
        waitFor("command output in PTY") { "ARGOS_42 ñ✓" in output }

        terminal.resize(100, 30)
        waitFor("window-change applied") {
            svc.capture("tmux list-clients -t term -F '#{client_width}x#{client_height}'").stdout.trim() == "100x30"
        }

        terminal.close()
        reader.join(5_000)
        waitFor("client detached") { svc.capture("tmux list-clients -t term -F x").stdout.isBlank() }
        val session = svc.listSessions().single { it.name == "term" }
        assertFalse("detach must keep the session alive and unattached", session.isAttached)
        svc.killSession("term")
    }

    @Test
    fun `i tmux detach ends the PTY stream cleanly`() = runBlocking<Unit> {
        val svc = service()
        svc.createSession("bye")
        val terminal = svc.openTerminal("bye", 80, 24)
        waitFor("attached client") { svc.capture("tmux list-clients -t bye -F x").stdout.isNotBlank() }
        svc.capture("tmux detach-client -s bye")
        val buffer = ByteArray(4096)
        val start = System.currentTimeMillis()
        var eof = false
        while (System.currentTimeMillis() - start < 10_000) {
            if (terminal.output.read(buffer) < 0) { eof = true; break }
        }
        assertTrue("EOF after detach", eof)
        terminal.close()
        assertTrue(svc.listSessions().any { it.name == "bye" })
        svc.killSession("bye")
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: suspend () -> Boolean) = runBlocking {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(200)
        }
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private val port get() = System.getenv("ARGOS_IT_PORT")!!.toInt()
        private val keysDir get() = File(System.getenv("ARGOS_IT_KEYS")!!)

        @JvmStatic
        @BeforeClass
        fun setUp() {
            assumeTrue("Set ARGOS_IT_PORT (see it/run-integration.sh)", System.getenv("ARGOS_IT_PORT") != null)
            ArgosApplication.installBouncyCastle()
        }
    }
}
