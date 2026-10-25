package dev.alore.argos.ssh

/**
 * Remote paths for uploaded files. /tmp is always writable and absolute (no $HOME resolution);
 * since it is shared, names get a random token and SshService creates them exclusively with 0600.
 */
object RemoteFiles {
    private val ALLOWED = Regex("[^A-Za-z0-9._-]")

    /** Keeps only `[A-Za-z0-9._-]` so the path can be inserted in the terminal without quoting. */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/')
        return base.replace(ALLOWED, "_").ifEmpty { "archivo" }
    }

    fun pastedImagePath(extension: String, nowMillis: Long = System.currentTimeMillis(), token: String = randomToken()): String =
        "/tmp/argos-pasted-$nowMillis-$token.${sanitize(extension)}"

    fun uploadedFilePath(originalName: String, nowMillis: Long = System.currentTimeMillis(), token: String = randomToken()): String =
        "/tmp/argos-$nowMillis-$token-${sanitize(originalName)}"

    private val random = java.security.SecureRandom()

    /** 8 lowercase hex chars: makes the path unguessable for other users of the server. */
    fun randomToken(): String = "%08x".format(random.nextInt())
}
