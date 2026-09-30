package dev.mateuy.panoptes.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mateuy.panoptes.application.CredentialField
import dev.mateuy.panoptes.desktop.theme.VisibilityIcon
import dev.mateuy.panoptes.desktop.theme.VisibilityOffIcon
import org.koin.compose.viewmodel.koinViewModel
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val STORE_TITLES = mapOf(
    "googleplay" to "Google Play",
    "appstore" to "App Store",
    "microsoft" to "Microsoft Store",
    "snap" to "Snap Store",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.savedCount) {
        if (state.savedCount > 0) onSaved()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.isSaving) viewModel.save() },
                icon = {
                    if (state.isSaving) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Done, contentDescription = null)
                },
                text = { Text("Save") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.fields.groupBy { it.field.storeKey }.forEach { (storeKey, fields) ->
                    item(key = storeKey) {
                        Text(
                            STORE_TITLES[storeKey] ?: storeKey,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    items(fields, key = { "${it.field.storeKey}.${it.field.credKey}" }) { field ->
                        CredentialInput(
                            state = field,
                            enabled = !state.isSaving && field.isEditable,
                            onInputChange = { viewModel.onInputChange(field.field, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialInput(state: FieldState, enabled: Boolean, onInputChange: (String) -> Unit) {
    val field = state.field
    val supportingText = when {
        state.error != null -> state.error
        !state.isEditable -> if (state.isSet) "From .env" else "Not in .env"
        state.isSuggested -> "From .env — save to keep it"
        field.kind == CredentialField.Kind.SECRET && state.isSet -> "Saved — leave empty to keep it"
        field.kind == CredentialField.Kind.FILE && state.isSet -> "Loaded — choose a file to replace it"
        else -> null
    }

    val isSecret = field.kind == CredentialField.Kind.SECRET
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = state.input,
        onValueChange = onInputChange,
        label = { Text(field.label.removeSuffix(" path")) },
        placeholder = field.hint.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = state.error != null,
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isSecret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = when (field.kind) {
            CredentialField.Kind.FILE -> {
                {
                    TextButton(onClick = { chooseFile(field.label)?.let(onInputChange) }, enabled = enabled) {
                        Text("Browse…")
                    }
                }
            }
            CredentialField.Kind.SECRET -> {
                {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) VisibilityOffIcon else VisibilityIcon,
                            contentDescription = if (revealed) "Hide" else "Show",
                        )
                    }
                }
            }
            CredentialField.Kind.TEXT -> null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun chooseFile(title: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}
