package dev.alore.argos.ui.sessions

import dev.alore.argos.model.TmuxSession
import dev.alore.argos.ssh.SshError
import dev.alore.argos.ssh.TmuxRemote
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLoaderTest {
    private class FakeRemote(
        var installed: Boolean = true,
        val sudo: Boolean = false,
        var configExists: Boolean = true,
        val sessions: List<TmuxSession> = listOf(TmuxSession("main", 1, false, 0)),
        val listError: Exception? = null,
    ) : TmuxRemote {
        val calls = mutableListOf<String>()
        override suspend fun listSessions(): List<TmuxSession> { calls += "list"; listError?.let { throw it }; return sessions }
        override suspend fun isTmuxInstalled() = installed.also { calls += "installed?" }
        override suspend fun canUseSudoNonInteractive() = sudo.also { calls += "sudo?" }
        override suspend fun installTmuxWithApt() { calls += "install"; installed = true }
        override suspend fun tmuxConfigExists() = configExists.also { calls += "config?" }
        override suspend fun writeDefaultTmuxConfig() { calls += "writeConfig"; configExists = true }
        override suspend fun enableClipboardForwarding() { calls += "clipboard" }
        override suspend fun createSession(name: String) = Unit
        override suspend fun renameSession(oldName: String, newName: String) = Unit
        override suspend fun killSession(name: String) = Unit
    }

    private suspend fun run(remote: FakeRemote): Pair<List<LoadState>, LoadState> {
        val states = mutableListOf<LoadState>()
        val final = SessionLoader.load(remote) { states += it }
        return states to final
    }

    @Test
    fun `ready server just verifies and lists`() = runTest {
        val remote = FakeRemote()
        val (states, final) = run(remote)
        assertEquals(listOf(LoadState.Verifying, LoadState.Loading, final), states)
        assertEquals(LoadState.Loaded(remote.sessions), final)
        assertEquals(listOf("installed?", "config?", "clipboard", "list"), remote.calls)
    }

    @Test
    fun `missing tmux with sudo installs and configures`() = runTest {
        val remote = FakeRemote(installed = false, sudo = true, configExists = false)
        val (states, final) = run(remote)
        assertEquals(
            listOf(LoadState.Verifying, LoadState.Installing, LoadState.Configuring, LoadState.Loading, final),
            states,
        )
        assertEquals(LoadState.Loaded(remote.sessions), final)
    }

    @Test
    fun `missing tmux without sudo stops with manual instruction`() = runTest {
        val remote = FakeRemote(installed = false, sudo = false)
        val (states, final) = run(remote)
        assertEquals(LoadState.TmuxMissing, final)
        assertEquals(listOf(LoadState.Verifying, LoadState.TmuxMissing), states)
        assertEquals(listOf("installed?", "sudo?"), remote.calls)
    }

    @Test
    fun `failures become a user message`() = runTest {
        val (_, final) = run(FakeRemote(listError = SshError.AuthFailed(null)))
        assertEquals(LoadState.Failed(SshError.AuthFailed(null).message!!), final)
    }

    @Test
    fun `unexpected errors are not leaked to the user`() = runTest {
        val (_, final) = run(FakeRemote(listError = IllegalStateException("NPE at Foo.kt:12")))
        assertEquals(LoadState.Failed("Algo salió mal. Intenta de nuevo."), final)
    }
}
