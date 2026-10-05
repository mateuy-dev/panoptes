package dev.mateuy.panoptes.infrastructure

import net.peanuuutz.tomlkt.Toml
import kotlinx.serialization.Serializable
import java.io.File

/** Contents of `.panoptes/config.toml`. The file is meant to be committed, so it must never hold secrets. */
@Serializable
data class AppConfig(
    val app: AppIdentifiers = AppIdentifiers(),
)

/** The app's id in each store, keyed by the same store keys as the credentials. */
@Serializable
data class AppIdentifiers(
    val googlePlayPackageName: String = "",
    val appStoreBundleId: String = "",
    val windowsStoreId: String = "",
    val snapName: String = "",
    val dmgReleaseUrl: String = "",
) {
    fun forStore(storeKey: String): String = when (storeKey) {
        "googleplay" -> googlePlayPackageName
        "appstore" -> appStoreBundleId
        "microsoft" -> windowsStoreId
        "snap" -> snapName
        "dmg" -> dmgReleaseUrl
        else -> error("Unknown store: $storeKey")
    }

    fun withStore(storeKey: String, value: String): AppIdentifiers = when (storeKey) {
        "googleplay" -> copy(googlePlayPackageName = value)
        "appstore" -> copy(appStoreBundleId = value)
        "microsoft" -> copy(windowsStoreId = value)
        "snap" -> copy(snapName = value)
        "dmg" -> copy(dmgReleaseUrl = value)
        else -> error("Unknown store: $storeKey")
    }
}

class ConfigReader(private val configFile: File) {
    private val toml = Toml

    val isFirstRun: Boolean
        get() = !configFile.exists()

    fun load(): AppConfig {
        if (!configFile.exists()) return AppConfig()
        return toml.decodeFromString(AppConfig.serializer(), configFile.readText())
    }

    /** The app's id in [storeKey]'s store, or "" when it isn't configured. */
    fun appId(storeKey: String): String = load().app.forStore(storeKey)

    fun save(config: AppConfig) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(toml.encodeToString(AppConfig.serializer(), config))
    }
}
