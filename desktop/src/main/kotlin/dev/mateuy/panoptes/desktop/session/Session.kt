package dev.mateuy.panoptes.desktop.session

import dev.mateuy.panoptes.di.appModule
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.EnvCredentialStore
import dev.mateuy.panoptes.infrastructure.Project
import org.koin.core.Koin

/** Opens the project's credentials, from its `.env` or its encrypted store, and makes the store modules available. */
class Session(private val koin: Koin, val project: Project) {

    /** The credentials come from the project's `.env`, so there's no master password. */
    val usesEnvFile: Boolean = project.usesEnvFile

    val hasCredentials: Boolean get() = project.credentialsFile.exists()

    /** Set once opened when git would commit the project's credentials file. */
    var credentialsWarning: String? = null
        private set

    /**
     * Blocking (key derivation is deliberately slow). Throws when the password can't decrypt the existing
     * credentials file. Returns true on first run, when the stores haven't been configured yet.
     */
    fun unlock(masterPassword: String): Boolean {
        val store = project.encryptedCredentialStore(masterPassword)
        try {
            store.load()
        } catch (e: Exception) {
            throw IllegalArgumentException("Wrong password or corrupted credentials file", e)
        }
        return open(store)
    }

    /** Blocking (runs dotenvx). Throws when the `.env` can't be decrypted. Returns true on first run. */
    fun openEnvFile(): Boolean = open(EnvCredentialStore.load(project.envFile))

    private fun open(store: CredentialStore): Boolean {
        koin.loadModules(listOf(appModule(project, store)))
        credentialsWarning = project.credentialsGitWarning()
        return koin.get<ConfigReader>().isFirstRun
    }
}
