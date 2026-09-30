package dev.mateuy.panoptes.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mateuy.panoptes.desktop.di.desktopModule
import dev.mateuy.panoptes.infrastructure.Project
import org.koin.core.context.startKoin

/** Opens the project in the working directory, or the one named by `--project <dir>` or PANOPTES_PROJECT. */
fun main(args: Array<String>) {
    val projectArg = args.indexOf("--project").takeIf { it != -1 }?.let { args.getOrNull(it + 1) }
    val project = Project.resolve(projectArg ?: System.getenv("PANOPTES_PROJECT"))

    // The store modules need the credentials, so they're loaded by Session once the user unlocks
    startKoin { modules(desktopModule(project)) }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Panoptes — ${project.dir.name}",
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
        ) {
            PanoptesApp()
        }
    }
}
