package dev.mateuy.panoptes.desktop.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mateuy.panoptes.desktop.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UnlockUiState(
    /** Folder of the project being opened. */
    val projectPath: String,
    /** The credentials come from the project's `.env`: no password, they're loaded straight away. */
    val usesEnvFile: Boolean,
    /** No credentials file yet: the password being entered creates it. */
    val isNewStore: Boolean,
    val password: String = "",
    val confirmation: String = "",
    val isUnlocking: Boolean = false,
    val error: String? = null,
    /** Set once unlocked; true on first run. */
    val unlocked: Boolean? = null,
) {
    val canSubmit get() = password.isNotEmpty() && (!isNewStore || confirmation.isNotEmpty()) && !isUnlocking
}

class UnlockViewModel(private val session: Session) : ViewModel() {

    private val _state = MutableStateFlow(
        UnlockUiState(
            projectPath = session.project.dir.path,
            usesEnvFile = session.usesEnvFile,
            isNewStore = !session.hasCredentials,
        ),
    )
    val state: StateFlow<UnlockUiState> = _state.asStateFlow()

    init {
        if (session.usesEnvFile) openEnvFile()
    }

    /** Loads the credentials from the project's `.env`; also the retry after an error. */
    fun openEnvFile() = open { session.openEnvFile() }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onConfirmationChange(value: String) = _state.update { it.copy(confirmation = value, error = null) }

    fun unlock() {
        val current = _state.value
        if (!current.canSubmit) return
        if (current.isNewStore && current.password != current.confirmation) {
            _state.update { it.copy(error = "Passwords don't match") }
            return
        }

        open { session.unlock(current.password) }
    }

    /** Runs [opening] in the background; it returns whether it's the first run. */
    private fun open(opening: () -> Boolean) {
        _state.update { it.copy(isUnlocking = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(opening) }
            _state.update { state ->
                result.fold(
                    onSuccess = { firstRun -> state.copy(isUnlocking = false, unlocked = firstRun) },
                    onFailure = { e -> state.copy(isUnlocking = false, error = e.message ?: "Couldn't unlock") },
                )
            }
        }
    }
}
