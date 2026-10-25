package dev.alore.argos.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthMethod {
    /** Private key (OpenSSH/PEM), optional passphrase. Both live encrypted in SecretStore. */
    @SerialName("key") KEY,

    /** Username + password (password lives encrypted in SecretStore). */
    @SerialName("password") PASSWORD,
}

/**
 * A configurable, persisted SSH server. Secrets (private key text, passphrase, password) are NOT
 * stored here: they live encrypted in SecretStore, keyed by [id].
 */
@Serializable
data class Server(
    val id: String = UUID.randomUUID().toString(),
    /** Display name ("dev", "producción"…). */
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.KEY,
    /** Display-only label of the imported key (e.g. its file name). */
    val keyLabel: String = "",
) {
    /** "host:port" — identity for TOFU host key verification. */
    val endpoint: String get() = "$host:$port"
}
