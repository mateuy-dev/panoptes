package dev.mateuy.panoptes

import dev.mateuy.panoptes.di.appModule
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.domain.port.StoreAdapter
import dev.mateuy.panoptes.application.PromoteBuildUseCase
import dev.mateuy.panoptes.application.ViewVersionsUseCase
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.EnvCredentialStore
import dev.mateuy.panoptes.infrastructure.Project
import dev.mateuy.panoptes.ui.importFromEnv
import dev.mateuy.panoptes.ui.runCli
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Usage: `panoptes [--project <dir>] [--import-env]`. The project is the working directory (or the closest parent
 * with a `.panoptes/` folder or `.env`) unless `--project` or `PANOPTES_PROJECT` names one.
 */
fun main(args: Array<String> = emptyArray()) {
    val importEnv = "--import-env" in args
    val project = Project.resolve(args.optionValue("--project") ?: System.getenv("PANOPTES_PROJECT"))
    println("Project: ${project.dir}")

    // --import-env copies the environment into credentials.enc, so it always uses the encrypted store
    val credentialStore = if (project.usesEnvFile && !importEnv) {
        println("Credentials: ${project.envFile.name} (dotenvx)")
        try {
            EnvCredentialStore.load(project.envFile)
        } catch (e: Exception) {
            fail(e.message ?: "Couldn't read ${project.envFile.name}")
        }
    } else {
        openEncryptedStore(project)
    }

    project.credentialsGitWarning()?.let { System.err.println("Warning: $it") }

    val koin = startKoin { modules(appModule(project, credentialStore)) }.koin
    val configReader = koin.get<ConfigReader>()

    if (importEnv) {
        importFromEnv(credentialStore, configReader)
        stopKoin()
        return
    }

    val adapters = koin.get<List<StoreAdapter>>()
    val viewVersionsUseCase = koin.get<ViewVersionsUseCase>()
    val promoteBuildUseCase = koin.get<PromoteBuildUseCase>()

    runCli(configReader, credentialStore, adapters, viewVersionsUseCase, promoteBuildUseCase)

    stopKoin()
}

private fun openEncryptedStore(project: Project): CredentialStore {
    val store = project.encryptedCredentialStore(readMasterPassword())
    try {
        store.load()
    } catch (e: Exception) {
        fail("Wrong password or corrupted credentials file.")
    }
    return store
}

private fun Array<String>.optionValue(name: String): String? {
    val index = indexOf(name)
    if (index == -1) return null
    return getOrNull(index + 1) ?: fail("$name needs a value")
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    kotlin.system.exitProcess(1)
}

private fun readMasterPassword(): String {
    print("Master password: ")
    // On JDK 22+ System.console() may be non-null without a real terminal, and readPassword() returns null on EOF
    val password = System.console()?.readPassword()?.let(::String) ?: readLine()
    if (password.isNullOrEmpty()) fail("No master password provided.")
    return password
}
