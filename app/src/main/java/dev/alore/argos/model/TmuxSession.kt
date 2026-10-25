package dev.alore.argos.model

/**
 * A tmux session on the remote server. Parsed from the EXACT format emitted by:
 *   tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_attached}|#{session_created}'
 */
data class TmuxSession(
    /** `#{session_name}` — unique per tmux server; also the session's identity. */
    val name: String,
    /** `#{session_windows}` — number of windows. */
    val windowCount: Int,
    /** `#{session_attached}` — true when at least one client is attached. */
    val isAttached: Boolean,
    /** `#{session_created}` — creation time, epoch seconds. */
    val createdAtEpochSeconds: Long,
) {
    companion object {
        const val FORMAT = "#{session_name}|#{session_windows}|#{session_attached}|#{session_created}"

        /**
         * Parses one line of `tmux list-sessions -F` output (4 `|`-separated fields).
         * Returns null for empty or malformed lines so the rest can still be parsed.
         */
        fun parse(line: String): TmuxSession? {
            val raw = line.trim()
            if (raw.isEmpty()) return null
            val fields = raw.split('|')
            if (fields.size != 4) return null
            val name = fields[0]
            if (name.isEmpty()) return null
            val windows = fields[1].toIntOrNull() ?: return null
            val attached = fields[2].toIntOrNull() ?: return null
            val created = fields[3].toLongOrNull() ?: return null
            return TmuxSession(name, windows, attached != 0, created)
        }

        fun parseList(output: String): List<TmuxSession> = output.lineSequence().mapNotNull(::parse).toList()
    }
}
