package dev.alore.argos.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.termux.view.TerminalView
import dev.alore.argos.ArgosApplication
import dev.alore.argos.ServerSecrets
import dev.alore.argos.model.Server
import dev.alore.argos.ssh.SshService
import dev.alore.argos.testing.FakeAndroidKeyStore
import dev.alore.argos.ui.sessions.LoadState
import dev.alore.argos.ui.sessions.SessionLoader
import dev.alore.argos.ui.theme.ArgosTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app UI (Compose + Termux TerminalView, rendered natively by Robolectric)
 * against the throwaway sshd from `it/run-integration.sh`, and saves screenshots to
 * `app/build/screenshots/` for visual review. Skipped unless ARGOS_IT_PORT is set.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "es-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppEndToEndTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: SshService

    @Before
    fun setUp() {
        assumeTrue("Set ARGOS_IT_PORT (see it/run-integration.sh)", System.getenv("ARGOS_IT_PORT") != null)
        FakeAndroidKeyStore.install()
        val container = ApplicationProvider.getApplicationContext<ArgosApplication>().container
        val server = Server(name = "docker-it", host = "127.0.0.1", port = System.getenv("ARGOS_IT_PORT")!!.toInt(), username = "argos", keyLabel = "id_ed25519")
        val key = File(System.getenv("ARGOS_IT_KEYS"), "id_ed25519").readText()
        container.saveServer(server, ServerSecrets(key, null, null))
        service = SshService(container.configFor(server), dev.alore.argos.ssh.KnownHostsStore(File.createTempFile("argos-kh", ".json").apply { delete() }))
        runBlocking {
            // Make sure tmux exists (the bootstrap installs it on a fresh container).
            check(SessionLoader.load(service) {} is LoadState.Loaded)
            for (name in listOf("e2e/demo", "e2e/api", "logs")) runCatching { service.createSession(name) }
        }
    }

    @After
    fun tearDown() = runBlocking {
        if (::service.isInitialized) {
            for (name in listOf("e2e/demo", "e2e/api", "logs")) runCatching { service.killSession(name) }
            service.disconnect()
        }
    }

    @Test
    fun browseSessionsOpenTerminalAndDetach() {
        compose.setContent { ArgosTheme { ArgosNavHost() } }

        // Home: real sessions from the server, grouped by prefix.
        compose.waitUntilAtLeastOneExists(hasText("demo"), timeoutMillis = 60_000)
        compose.waitUntilAtLeastOneExists(hasText("e2e"))
        screenshot("01-sessions")

        // Open the terminal: PTY + tmux attach.
        compose.onNode(hasText("demo")).performClick()
        compose.waitUntilDoesNotExist(hasText("Conectando a la sesión"), timeoutMillis = 30_000)
        runBlocking {
            // ASCII-only command line (the container's shell has no UTF-8 locale); the output
            // exercises ANSI colors, box drawing and the symbols Claude Code uses.
            val demo = "clear; tmux ls; ls --color=always / | head -6 | tr '\\n' ' '; echo; " +
                "printf '\\033[31mrojo \\033[32mverde \\033[33mamarillo \\033[34mazul \\033[35mmagenta \\033[36mcian \\033[1;37mnegrita\\033[0m\\n'; " +
                "printf '\\342\\225\\255\\342\\224\\200 Claude Code \\342\\224\\200\\342\\225\\256\\n\\342\\234\\273 Listo \\342\\217\\272 hola desde Argos \\342\\234\\223\\n  \\342\\216\\277 Read 3 files \\342\\240\\213 \\342\\217\\270 \\342\\234\\224\\n'"
            service.capture("tmux send-keys -t 'e2e/demo' ${dev.alore.argos.model.ShellQuoting.singleQuoted(demo)} Enter")
        }
        compose.waitUntil(15_000) { terminalText().contains("hola desde Argos ✓") }
        compose.waitUntil(15_000) { terminalText().contains("e2e/api:") }
        screenshot("02-terminal")

        // Extra keys row: smart paste + Enter really reach the shell.
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        compose.runOnUiThread { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", "echo pegado-\$((6*7))")) }
        compose.onNode(hasContentDescription("Pegar desde el portapapeles")).performClick()
        compose.onNode(hasContentDescription("Enter")).performClick()
        compose.waitUntil(15_000) { terminalText().contains("pegado-42") }

        // Back = detach: the session survives and no client stays attached.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasText("demo"), timeoutMillis = 15_000)
        compose.waitUntil(15_000) {
            runBlocking { service.capture("tmux list-clients -t 'e2e/demo' -F x").stdout.isBlank() }
        }
        assertEquals(false, runBlocking { service.listSessions().single { it.name == "e2e/demo" }.isAttached })

        compose.onNode(hasContentDescription("Añadir servidor")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Nuevo servidor"))
        screenshot("03-server-form")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntilAtLeastOneExists(hasContentDescription("Ajustes"))
        compose.onNode(hasContentDescription("Ajustes")).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Tamaño de fuente"))
        screenshot("04-settings")
    }

    private fun terminalText(): String {
        compose.waitForIdle()
        val view = findTerminalView(compose.activity.window.decorView) ?: return ""
        return view.mEmulator?.screen?.transcriptText.orEmpty()
    }

    private fun findTerminalView(view: View): TerminalView? = when (view) {
        is TerminalView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTerminalView(view.getChildAt(it)) }
        else -> null
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
