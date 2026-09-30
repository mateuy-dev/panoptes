package dev.mateuy.panoptes.domain.port

interface CredentialStore {
    /** Whether the credentials are managed elsewhere (e.g. a `.env`), so [set] and [save] aren't supported. */
    val isReadOnly: Boolean get() = false

    /** An app id the credentials' source suggests for [store], e.g. BUNDLE_ID in a `.env`; only a default to confirm. */
    fun suggestedAppId(store: String): String? = null

    fun get(store: String, key: String): String?
    fun set(store: String, key: String, value: String)
    fun getAll(store: String): Map<String, String>
    fun save()
    fun load()
}
