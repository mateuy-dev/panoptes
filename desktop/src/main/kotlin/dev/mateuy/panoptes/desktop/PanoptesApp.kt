package dev.mateuy.panoptes.desktop

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.mateuy.panoptes.desktop.dashboard.DashboardScreen
import dev.mateuy.panoptes.desktop.session.Session
import dev.mateuy.panoptes.desktop.settings.SettingsScreen
import dev.mateuy.panoptes.desktop.theme.PanoptesTheme
import dev.mateuy.panoptes.desktop.unlock.UnlockScreen
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object UnlockRoute

@Serializable
object DashboardRoute

@Serializable
object SettingsRoute

/** Set on the dashboard's back stack entry when settings were saved, so it reloads the versions. */
const val SETTINGS_SAVED_KEY = "settingsSaved"

@Composable
fun PanoptesApp() {
    PanoptesTheme {
        val navController = rememberNavController()
        val session = koinInject<Session>()

        NavHost(navController, startDestination = UnlockRoute) {
            composable<UnlockRoute> {
                UnlockScreen(
                    onUnlocked = { firstRun ->
                        navController.navigate(DashboardRoute) {
                            popUpTo(UnlockRoute) { inclusive = true }
                        }
                        if (firstRun) navController.navigate(SettingsRoute)
                    },
                )
            }
            composable<DashboardRoute> { entry ->
                DashboardScreen(
                    savedStateHandle = entry.savedStateHandle,
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    warning = session.credentialsWarning,
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { navController.navigateUp() },
                    onSaved = {
                        navController.previousBackStackEntry?.savedStateHandle?.set(SETTINGS_SAVED_KEY, true)
                    },
                )
            }
        }
    }
}
