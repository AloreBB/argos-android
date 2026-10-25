package dev.alore.argos.ssh

/** Remote paths for uploaded files. /tmp is always writable and absolute (no $HOME resolution). */
object RemoteFiles {
    private val ALLOWED = Regex("[^A-Za-z0-9._-]")

    /** Keeps only `[A-Za-z0-9._-]` so the path can be inserted in the terminal without quoting. */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/')
        return base.replace(ALLOWED, "_").ifEmpty { "archivo" }
    }

    fun pastedImagePath(extension: String, nowMillis: Long = System.currentTimeMillis()): String =
        "/tmp/argos-pasted-$nowMillis.${sanitize(extension)}"

    fun uploadedFilePath(originalName: String, nowMillis: Long = System.currentTimeMillis()): String =
        "/tmp/argos-$nowMillis-${sanitize(originalName)}"
}
