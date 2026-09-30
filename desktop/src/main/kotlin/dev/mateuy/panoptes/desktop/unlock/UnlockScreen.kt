package dev.mateuy.panoptes.desktop.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun UnlockScreen(
    onUnlocked: (firstRun: Boolean) -> Unit,
    viewModel: UnlockViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.unlocked) {
        state.unlocked?.let(onUnlocked)
    }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (state.usesEnvFile) {
                EnvFileLoading(state, onRetry = viewModel::openEnvFile)
            } else {
                UnlockForm(
                    state = state,
                    onPasswordChange = viewModel::onPasswordChange,
                    onConfirmationChange = viewModel::onConfirmationChange,
                    onSubmit = viewModel::unlock,
                )
            }
        }
    }
}

@Composable
private fun EnvFileLoading(state: UnlockUiState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.width(420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Panoptes", style = MaterialTheme.typography.headlineMedium)
        ProjectPath(state.projectPath)
        val error = state.error
        if (error == null) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(
                "Loading credentials from .env…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRetry, enabled = !state.isUnlocking) { Text("Retry") }
        }
    }
}

@Composable
private fun ProjectPath(path: String) {
    Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UnlockForm(
    state: UnlockUiState,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val passwordOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)

    Column(
        modifier = Modifier.width(360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Panoptes", style = MaterialTheme.typography.headlineMedium)
        ProjectPath(state.projectPath)
        Text(
            if (state.isNewStore) "Choose a master password to encrypt your store credentials."
            else "Enter your master password to unlock your store credentials.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text("Master password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = passwordOptions,
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            isError = state.error != null && !state.isNewStore,
            enabled = !state.isUnlocking,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        if (state.isNewStore) {
            OutlinedTextField(
                value = state.confirmation,
                onValueChange = onConfirmationChange,
                label = { Text("Confirm password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = passwordOptions,
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                isError = state.error != null,
                enabled = !state.isUnlocking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(4.dp))
        Button(onClick = onSubmit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (state.isUnlocking) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (state.isNewStore) "Create" else "Unlock")
            }
        }
    }
}
