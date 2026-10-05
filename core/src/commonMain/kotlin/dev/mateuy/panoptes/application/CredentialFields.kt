package dev.mateuy.panoptes.application

import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.infrastructure.ConfigReader

/** A setting editable from the settings screens (CLI wizard and desktop UI). */
data class CredentialField(
    val storeKey: String,
    val credKey: String,
    val label: String,
    val kind: Kind = Kind.TEXT,
    val hint: String = "",
    /** The app's id in the store: kept in the committed config.toml instead of the credential store. */
    val isIdentifier: Boolean = false,
) {
    enum class Kind {
        TEXT,

        /** Never displayed back; left unchanged when no new value is entered. */
        SECRET,

        /** Entered as a file path; the file's contents are stored. */
        FILE,
    }
}

val CREDENTIAL_FIELDS = listOf(
    CredentialField("googleplay", "packageName", "Google Play Package Name", hint = "com.example.app", isIdentifier = true),
    CredentialField("googleplay", "serviceAccountJson", "Google Play Service Account JSON path", CredentialField.Kind.FILE, "/path/to/service-account.json"),
    CredentialField("appstore", "bundleId", "App Store Bundle ID", hint = "com.example.app", isIdentifier = true),
    CredentialField("appstore", "issuerId", "App Store Connect Issuer ID", hint = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"),
    CredentialField("appstore", "keyId", "App Store Connect Key ID", hint = "XXXXXXXXXX"),
    CredentialField("appstore", "privateKey", "App Store Connect .p8 key path", CredentialField.Kind.FILE, "/path/to/AuthKey.p8"),
    CredentialField("microsoft", "storeId", "Microsoft Store Product ID", hint = "XXXXXXXXXXX", isIdentifier = true),
    CredentialField("microsoft", "tenantId", "Azure Tenant ID", hint = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"),
    CredentialField("microsoft", "clientId", "Azure Client ID", hint = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"),
    CredentialField("microsoft", "clientSecret", "Azure Client Secret", CredentialField.Kind.SECRET),
    CredentialField("snap", "snapName", "Snap Name", hint = "my-snap", isIdentifier = true),
    CredentialField("snap", "macaroon", "Snapcraft Credentials (SNAPCRAFT_STORE_CREDENTIALS)", CredentialField.Kind.SECRET),
    CredentialField("dmg", "releaseUrl", "DMG Release URL", hint = "https://example.com/api/desktop_releases/mac-arm64", isIdentifier = true),
)

/** Whether [field] can be changed: credentials can't when they come from a `.env`. */
fun CredentialStore.canEdit(field: CredentialField) = field.isIdentifier || !isReadOnly

/** Current value of [field], or null when it isn't set. */
fun currentValue(field: CredentialField, credentialStore: CredentialStore, configReader: ConfigReader): String? =
    if (field.isIdentifier) configReader.appId(field.storeKey).ifEmpty { null }
    else credentialStore.get(field.storeKey, field.credKey)

/** A default to offer for [field] when it isn't set, e.g. the bundle id from a `.env`'s BUNDLE_ID. */
fun suggestedValue(field: CredentialField, credentialStore: CredentialStore): String? =
    if (field.isIdentifier) credentialStore.suggestedAppId(field.storeKey) else null

/**
 * Saves the new [values] (as stored, so file fields hold the file contents). Always writes config.toml, whose
 * presence marks setup as done.
 */
fun saveSettings(values: Map<CredentialField, String>, credentialStore: CredentialStore, configReader: ConfigReader) {
    val (identifiers, credentials) = values.entries.partition { it.key.isIdentifier }

    if (credentials.isNotEmpty()) {
        credentials.forEach { (field, value) -> credentialStore.set(field.storeKey, field.credKey, value) }
        credentialStore.save()
    }

    val config = configReader.load()
    val app = identifiers.fold(config.app) { app, (field, value) -> app.withStore(field.storeKey, value) }
    configReader.save(config.copy(app = app))
}
