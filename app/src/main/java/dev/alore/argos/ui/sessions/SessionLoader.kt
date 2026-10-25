package dev.alore.argos.ui.sessions

import android.util.Log
import dev.alore.argos.model.SessionNameValidator
import dev.alore.argos.model.TmuxSession
import dev.alore.argos.ssh.SshError
import dev.alore.argos.ssh.TmuxRemote
import dev.alore.argos.ssh.userMessage
import kotlinx.coroutines.CancellationException

/** Load state of one server's session list; the UI mirrors each bootstrap phase. */
sealed interface LoadState {
    data object Idle : LoadState
    data object Verifying : LoadState
    data object Installing : LoadState
    data object Configuring : LoadState
    data object Loading : LoadState
    /** tmux missing and no passwordless sudo: manual instruction. */
    data object TmuxMissing : LoadState
    data class Loaded(val sessions: List<TmuxSession>) : LoadState
    data class Failed(val message: String) : LoadState

    val isBusy: Boolean get() = this is Verifying || this is Installing || this is Configuring || this is Loading
}

object SessionLoader {

    /**
     * On connect: ensure tmux (detect → install if possible → configure), THEN list. Reports
     * every phase through [onState] and returns the final state.
     */
    suspend fun load(remote: TmuxRemote, onState: (LoadState) -> Unit): LoadState {
        val final = try {
            onState(LoadState.Verifying)
            if (!remote.isTmuxInstalled()) {
                if (!remote.canUseSudoNonInteractive()) {
                    LoadState.TmuxMissing.also(onState)
                    return LoadState.TmuxMissing
                }
                onState(LoadState.Installing)
                remote.installTmuxWithApt()
            }
            if (!remote.tmuxConfigExists()) {
                onState(LoadState.Configuring)
                remote.writeDefaultTmuxConfig()
            }
            // OSC 52 on servers whose config predates Argos (best-effort).
            remote.enableClipboardForwarding()

            onState(LoadState.Loading)
            LoadState.Loaded(remote.listSessions())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logUnexpected(e)
            LoadState.Failed(e.userMessage)
        }
        onState(final)
        return final
    }

    /** Re-lists without repeating the bootstrap. */
    suspend fun refresh(remote: TmuxRemote): LoadState = try {
        LoadState.Loaded(remote.listSessions())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logUnexpected(e)
        LoadState.Failed(e.userMessage)
    }

    /** SshErrors are logged where they happen; anything else would otherwise vanish behind a generic message. */
    private fun logUnexpected(e: Exception) {
        if (e !is SshError && e !is SessionNameValidator.ValidationError) Log.e("Argos/Sessions", "Unexpected load failure", e)
    }
}
