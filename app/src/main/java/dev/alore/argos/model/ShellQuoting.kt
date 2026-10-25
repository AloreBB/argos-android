package dev.alore.argos.model

/** POSIX shell quoting: the anti-injection boundary for everything sent to the remote shell. */
object ShellQuoting {
    /**
     * Wraps [value] in single quotes, escaping inner quotes with the canonical `'\''`. The result
     * is always ONE literal argument; `""` becomes `''`.
     */
    fun singleQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
