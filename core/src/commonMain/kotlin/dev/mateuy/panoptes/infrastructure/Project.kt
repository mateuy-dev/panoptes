package dev.mateuy.panoptes.infrastructure

import java.io.File

/**
 * The app project Panoptes works on. Its `.panoptes/` folder holds `config.toml` (committed) and, unless the
 * project's `.env` provides the credentials, `credentials.enc` (git-ignored).
 */
class Project(val dir: File) {
    val panoptesDir get() = File(dir, ".panoptes")
    val configFile get() = File(panoptesDir, "config.toml")
    val credentialsFile get() = File(panoptesDir, "credentials.enc")
    val envFile get() = File(dir, ".env")

    /** Whether the credentials come from the project's dotenvx `.env` instead of `credentials.enc`. */
    val usesEnvFile: Boolean get() = EnvCredentialStore.appliesTo(envFile)

    /** The encrypted credential store; saving it the first time also git-ignores it. */
    fun encryptedCredentialStore(masterPassword: String) =
        CredentialStoreImpl(masterPassword, credentialsFile.path, onSave = ::ignoreCredentials)

    private fun ignoreCredentials() {
        val gitignore = File(panoptesDir, ".gitignore")
        if (!gitignore.exists()) gitignore.writeText("${credentialsFile.name}\n")
    }

    /** A warning when `credentials.enc` exists and git would commit it; null otherwise, also outside git repos. */
    fun credentialsGitWarning(): String? {
        if (!credentialsFile.exists()) return null
        val exitCode = runCatching {
            ProcessBuilder("git", "check-ignore", "-q", credentialsFile.path)
                .directory(dir)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        }.getOrNull()
        // 0: ignored, 1: not ignored (or tracked), 128: not a git repo
        if (exitCode != 1) return null
        return "${credentialsFile.relativeTo(dir)} is not git-ignored; add it to .gitignore so it isn't committed"
    }

    companion object {
        /**
         * The project containing [start] (the working directory by default): the closest folder, [start] or one of its
         * parents, with a `.panoptes/` folder or a `.env` file, or [start] itself when there's none. The home folder
         * is skipped, so an old global `~/.panoptes` is never picked up.
         */
        fun resolve(start: String? = null): Project {
            val base = File(start ?: System.getProperty("user.dir")).absoluteFile.normalize()
            val home = File(System.getProperty("user.home")).absoluteFile.normalize()
            val found = generateSequence(base) { it.parentFile }
                .filter { it != home }
                .firstOrNull { File(it, ".panoptes").isDirectory || File(it, ".env").isFile }
            return Project(found ?: base)
        }
    }
}
