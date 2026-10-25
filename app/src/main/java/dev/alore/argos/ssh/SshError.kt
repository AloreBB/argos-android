package dev.alore.argos.ssh

import dev.alore.argos.model.SessionNameValidator

/** Typed SSH/tmux failures with actionable, user-facing messages (Spanish, like the UI). */
sealed class SshError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class KeyMissing : SshError(
        "Falta la clave privada de este servidor. Edítalo y vuelve a importarla.",
    )

    class KeyParseFailed(cause: Throwable?) : SshError(
        "No se pudo cargar la clave privada. Verifica que esté en formato OpenSSH/PEM " +
            "y, si tiene passphrase, que sea la correcta.",
        cause,
    )

    class HostUnreachable(endpoint: String, cause: Throwable?) : SshError(
        "No se pudo conectar a $endpoint. Revisa el host, el puerto y tu conexión de red.",
        cause,
    )

    class AuthFailed(cause: Throwable?) : SshError(
        "El servidor rechazó la autenticación. Revisa el usuario y la clave o contraseña.",
        cause,
    )

    class HostKeyMismatch(
        val endpoint: String,
        val storedFingerprint: String,
        val presentedFingerprint: String,
    ) : SshError(
        "La identidad del servidor ($endpoint) cambió. Esperada $storedFingerprint, " +
            "recibida $presentedFingerprint. Posible ataque MitM: conexión abortada. Si el cambio " +
            "es legítimo (p. ej. reinstalaste el servidor), usa \"Olvidar host key\" en el menú del servidor.",
    )

    class ConnectionFailed(cause: Throwable?) : SshError(
        "Se perdió o no se pudo establecer la conexión SSH. Intenta de nuevo.",
        cause,
    )

    class CommandFailed(val exitCode: Int, detail: String) : SshError(
        "El comando remoto falló (código $exitCode): $detail",
    )

    class TmuxNotInstalled : SshError("tmux no está instalado en el servidor.")

    class InstallFailed(detail: String) : SshError("No se pudo instalar tmux. $detail")

    class ConfigFailed(detail: String) : SshError("No se pudo crear ~/.tmux.conf. $detail")

    class UploadFailed(cause: Throwable?) : SshError(
        "No se pudo subir el archivo al servidor. Revisa la conexión e intenta de nuevo.",
        cause,
    )

    companion object {
        /** Shown when tmux is missing and there is no passwordless sudo (the app never asks for it). */
        const val MANUAL_INSTALL_INSTRUCTION =
            "tmux no está instalado y tu usuario no tiene sudo sin contraseña. " +
                "Instálalo con: sudo apt install tmux"
    }
}

/**
 * Single source of truth to turn any failure into UI text. Known errors carry their own message;
 * anything unexpected gets a generic one (details go to the log, never to the user).
 */
val Throwable.userMessage: String
    get() = when (this) {
        is SshError -> message.orEmpty()
        is SessionNameValidator.ValidationError -> message.orEmpty()
        else -> "Algo salió mal. Intenta de nuevo."
    }
