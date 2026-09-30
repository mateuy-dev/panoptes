package dev.mateuy.panoptes.desktop.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mateuy.panoptes.application.CREDENTIAL_FIELDS
import dev.mateuy.panoptes.application.CredentialField
import dev.mateuy.panoptes.application.canEdit
import dev.mateuy.panoptes.application.currentValue
import dev.mateuy.panoptes.application.saveSettings
import dev.mateuy.panoptes.application.suggestedValue
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.infrastructure.ConfigReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FieldState(
    val field: CredentialField,
    /** Text fields: the value. Secret fields: a replacement value. File fields: a path to load. */
    val input: String,
    /** Whether a value is already saved. */
    val isSet: Boolean,
    /** False for credentials that come from the project's `.env`. */
    val isEditable: Boolean = true,
    /** [input] was pre-filled from the `.env` and isn't saved yet. */
    val isSuggested: Boolean = false,
    val error: String? = null,
)

data class SettingsUiState(
    val fields: List<FieldState>,
    val isSaving: Boolean = false,
    /** One-off message for the snackbar. */
    val message: String? = null,
    /** Incremented on every successful save. */
    val savedCount: Int = 0,
)

class SettingsViewModel(
    private val credentialStore: CredentialStore,
    private val configReader: ConfigReader,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(fields = readFields()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onInputChange(field: CredentialField, input: String) = _state.update { state ->
        state.copy(fields = state.fields.map { if (it.field == field) it.copy(input = input, error = null, isSuggested = false) else it })
    }

    fun save() {
        if (_state.value.isSaving) return
        val fields = _state.value.fields

        val validated = fields.map { state ->
            val missingFile = state.field.kind == CredentialField.Kind.FILE &&
                state.input.isNotBlank() && !File(state.input.trim()).isFile
            if (missingFile) state.copy(error = "File not found") else state
        }
        if (validated.any { it.error != null }) {
            _state.update { it.copy(fields = validated) }
            return
        }

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val values = fields
                        .filter { it.isEditable && it.input.isNotBlank() } // empty keeps the current value
                        .associate { state ->
                            val value = state.input.trim()
                            state.field to when (state.field.kind) {
                                CredentialField.Kind.FILE -> File(value).readText()
                                CredentialField.Kind.TEXT, CredentialField.Kind.SECRET -> value
                            }
                        }
                    saveSettings(values, credentialStore, configReader)
                }
            }
            _state.update { state ->
                result.fold(
                    onSuccess = { state.copy(fields = readFields(), isSaving = false, message = "Settings saved", savedCount = state.savedCount + 1) },
                    onFailure = { e -> state.copy(isSaving = false, message = "Couldn't save settings: ${e.message}") },
                )
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun readFields() = CREDENTIAL_FIELDS.map { field ->
        val current = currentValue(field, credentialStore, configReader)
        // Pre-filled when unset; saving keeps it
        val suggestion = if (current.isNullOrEmpty()) suggestedValue(field, credentialStore) else null
        FieldState(
            field = field,
            input = if (field.kind == CredentialField.Kind.TEXT) current ?: suggestion.orEmpty() else "",
            isSet = !current.isNullOrEmpty(),
            isEditable = credentialStore.canEdit(field),
            isSuggested = suggestion != null,
        )
    }
}
