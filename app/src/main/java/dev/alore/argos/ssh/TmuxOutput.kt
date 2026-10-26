package dev.alore.argos.ssh

/** Pure classification of tmux command output (no network). */
object TmuxOutput {

    /**
     * tmux's "no server / no sessions" messages, which vary by version and mean an EMPTY list:
     *  - `no server running on /tmp/tmux-1000/default`
     *  - `error connecting to /tmp/tmux-1000/default (No such file or directory)`
     *  - `failed to connect to server`
     *  - `no sessions`
     */
    fun indicatesNoTmuxServer(stdout: String, stderr: String): Boolean {
        val haystack = "$stdout\n$stderr".lowercase()
        return listOf("no server running", "error connecting to", "failed to connect to server", "no sessions")
            .any { it in haystack }
    }

    /**
     * The `tmux` binary is missing (`bash: tmux: command not found`, `sh: tmux: not found`, exit 127).
     * Must be checked AFTER [indicatesNoTmuxServer].
     */
    fun indicatesTmuxNotInstalled(stdout: String, stderr: String, exitCode: Int): Boolean {
        val haystack = "$stdout\n$stderr".lowercase()
        if ("command not found" in haystack || "tmux: not found" in haystack) return true
        return exitCode == 127 && "tmux" in haystack
    }

    /** Base `~/.tmux.conf` (mouse, scrollback, base-index 1, truecolor, OSC 52 clipboard). */
    val DEFAULT_CONFIG = listOf(
        "set -g mouse on",
        "set -g history-limit 50000",
        "set -g base-index 1",
        "setw -g pane-base-index 1",
        "set -g renumber-windows on",
        "set -sg escape-time 0",
        "set -g default-terminal \"tmux-256color\"",
        "set -ga terminal-overrides \",*256col*:Tc\"",
        // Forward OSC 52 from remote apps (e.g. Claude Code's "c to copy") to the outer terminal,
        // which copies to the Android clipboard. `Ms` declares OSC 52 support explicitly.
        "set -g set-clipboard on",
        "set -ga terminal-overrides \",*:Ms=\\\\E]52;%p1%s;%p2%s\\\\007\"",
        // Pass OSC 8 hyperlinks (Claude Code's PR/branch links) through to the terminal (tmux 3.4+).
        "set -as terminal-features \",*:hyperlinks\"",
    ).joinToString("\n")

    /** Idempotent, race-safe write of [DEFAULT_CONFIG]: never overwrites an existing file. */
    val WRITE_DEFAULT_CONFIG_COMMAND =
        "if [ ! -f \"\$HOME/.tmux.conf\" ]; then\n" +
            "cat > \"\$HOME/.tmux.conf\" <<'ARGOS_TMUX_CONF'\n" +
            DEFAULT_CONFIG + "\n" +
            "ARGOS_TMUX_CONF\n" +
            "fi"

    /** Best-effort OSC 52 on an already running tmux server with an older config. */
    const val ENABLE_CLIPBOARD_COMMAND =
        "tmux set -g set-clipboard on 2>/dev/null; " +
            "tmux set -ga terminal-overrides ',*:Ms=\\E]52;%p1%s;%p2%s\\007' 2>/dev/null; true"

    const val LIST_SESSIONS_COMMAND = "tmux list-sessions -F '${dev.alore.argos.model.TmuxSession.FORMAT}'"

    const val INSTALL_WITH_APT_COMMAND =
        "sudo -n sh -c 'apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y tmux'"
}
