package dev.alore.argos.model

/**
 * Validates tmux session names. tmux uses ':' and '.' as target separators
 * (session:window.pane) so they are forbidden; '/' is allowed and used for grouping.
 */
object SessionNameValidator {

    sealed class ValidationError(message: String) : IllegalArgumentException(message) {
        data object Empty : ValidationError("El nombre no puede estar vacío.")
        data class ForbiddenCharacter(val character: Char) : ValidationError(
            "El nombre no puede contener '$character': tmux usa ':' y '.' como separadores de sesión.",
        )
        data object ControlCharacter : ValidationError(
            "El nombre no puede contener saltos de línea, tabuladores ni otros caracteres de control.",
        )
        data object LeadingDash : ValidationError(
            "El nombre no puede empezar por '-' (tmux lo interpretaría como una opción).",
        )
    }

    val FORBIDDEN = listOf(':', '.')

    /**
     * Trims and validates; returns the name ready for tmux or throws [ValidationError].
     * Defense in depth on top of [ShellQuoting]: control chars would break line-based parsing of
     * list-sessions and a leading '-' would inject tmux OPTIONS.
     */
    fun validate(rawName: String): String {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) throw ValidationError.Empty
        if (trimmed.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            throw ValidationError.ControlCharacter
        }
        FORBIDDEN.firstOrNull { it in trimmed }?.let { throw ValidationError.ForbiddenCharacter(it) }
        if (trimmed.startsWith('-')) throw ValidationError.LeadingDash
        return trimmed
    }

    fun isValid(rawName: String): Boolean = runCatching { validate(rawName) }.isSuccess

    /** Live form feedback; null when valid OR still empty (no error before the user types). */
    fun liveValidationMessage(rawName: String): String? = try {
        validate(rawName)
        null
    } catch (_: ValidationError.Empty) {
        null
    } catch (e: ValidationError) {
        e.message
    }
}
