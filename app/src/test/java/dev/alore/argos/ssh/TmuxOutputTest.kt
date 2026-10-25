package dev.alore.argos.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxOutputTest {
    @Test
    fun `no-server markers mean an empty list`() {
        listOf(
            "no server running on /tmp/tmux-1000/default",
            "error connecting to /tmp/tmux-1000/default (No such file or directory)",
            "failed to connect to server",
            "no sessions",
        ).forEach { assertTrue(it, TmuxOutput.indicatesNoTmuxServer("", it)) }
    }

    @Test
    fun `normal output is not no-server`() = assertFalse(TmuxOutput.indicatesNoTmuxServer("main|1|0|1700000000", ""))

    @Test
    fun `command not found means not installed`() =
        assertTrue(TmuxOutput.indicatesTmuxNotInstalled("", "bash: tmux: command not found", 127))

    @Test
    fun `exit 127 mentioning tmux means not installed`() =
        assertTrue(TmuxOutput.indicatesTmuxNotInstalled("", "tmux: not found", 127))

    @Test
    fun `unrelated error is not tmux missing`() =
        assertFalse(TmuxOutput.indicatesTmuxNotInstalled("", "permission denied", 1))

    @Test
    fun `default config write never overwrites`() {
        assertTrue(TmuxOutput.WRITE_DEFAULT_CONFIG_COMMAND.startsWith("if [ ! -f \"\$HOME/.tmux.conf\" ]"))
        assertTrue("set -g set-clipboard on" in TmuxOutput.DEFAULT_CONFIG)
    }
}
