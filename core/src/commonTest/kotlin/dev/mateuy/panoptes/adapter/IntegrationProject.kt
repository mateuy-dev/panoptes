package dev.mateuy.panoptes.adapter

import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.EnvCredentialStore
import dev.mateuy.panoptes.infrastructure.Project

/**
 * The project the integration tests run against, named by PANOPTES_PROJECT (tests run from the module folder).
 * Credentials come from its `.env`, or from `credentials.enc` unlocked with PANOPTES_MASTER_PASSWORD.
 */
object IntegrationProject {
    val project: Project by lazy {
        Project.resolve(System.getenv("PANOPTES_PROJECT") ?: error("Set PANOPTES_PROJECT"))
    }

    val credentialStore: CredentialStore by lazy {
        if (project.usesEnvFile) {
            EnvCredentialStore.load(project.envFile)
        } else {
            val password = System.getenv("PANOPTES_MASTER_PASSWORD") ?: error("Set PANOPTES_MASTER_PASSWORD")
            project.encryptedCredentialStore(password).also { it.load() }
        }
    }

    val configReader: ConfigReader by lazy { ConfigReader(project.configFile) }
}
