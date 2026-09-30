package dev.mateuy.panoptes.infrastructure

import dev.mateuy.panoptes.domain.port.CredentialStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/** A credential kept in an environment variable, under the names the CI `.env` files use. */
data class EnvCredential(
    val storeKey: String,
    val credKey: String,
    val envVar: String,
    /** The value may be base64-encoded (a service account JSON or a PEM key); plain values are accepted too. */
    val base64: Boolean = false,
) {
    fun decode(raw: String): String {
        if (!base64) return raw
        return runCatching { String(Base64.getMimeDecoder().decode(raw.trim())) }
            .getOrNull()
            ?.takeIf { it.trimStart().startsWith("{") || it.contains("-----BEGIN") }
            ?: raw
    }
}

val ENV_CREDENTIALS = listOf(
    EnvCredential("googleplay", "serviceAccountJson", "PUBLISH_PLAY_CONFIG_JSON", base64 = true),
    EnvCredential("appstore", "issuerId", "APPSTORE_ISSUER_ID"),
    EnvCredential("appstore", "keyId", "APPSTORE_KEY_ID"),
    EnvCredential("appstore", "privateKey", "APPSTORE_PRIVATE_KEY_BASE64", base64 = true),
    EnvCredential("microsoft", "tenantId", "MS_STORE_TENANT_ID"),
    EnvCredential("microsoft", "clientId", "MS_STORE_CLIENT_ID"),
    EnvCredential("microsoft", "clientSecret", "MS_STORE_CLIENT_SECRET"),
    EnvCredential("snap", "macaroon", "SNAPCRAFT_STORE_CREDENTIALS"),
)

/** App ids some `.env` files carry for CI, by store key. */
private val ENV_APP_IDS = mapOf("appstore" to "BUNDLE_ID")

/** Read-only credentials taken from environment variables, e.g. a project's dotenvx `.env`. */
class EnvCredentialStore(private val env: Map<String, String>) : CredentialStore {

    private val data: Map<String, Map<String, String>> = ENV_CREDENTIALS
        .mapNotNull { credential -> env[credential.envVar]?.let { credential to credential.decode(it) } }
        .groupBy({ it.first.storeKey }, { it.first.credKey to it.second })
        .mapValues { (_, pairs) -> pairs.toMap() }

    override val isReadOnly: Boolean get() = true

    override fun suggestedAppId(store: String): String? = ENV_APP_IDS[store]?.let(env::get)?.ifBlank { null }

    override fun get(store: String, key: String): String? = data[store]?.get(key)

    override fun getAll(store: String): Map<String, String> = data[store].orEmpty()

    override fun set(store: String, key: String, value: String) = readOnly()

    override fun save() = readOnly()

    override fun load() {}

    private fun readOnly(): Nothing =
        throw UnsupportedOperationException("Credentials come from .env; change them with dotenvx")

    companion object {
        private val VARIABLE_NAME = Regex("""^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=""")

        /**
         * Whether [envFile] defines at least one credential variable. Only the names are read, which dotenvx leaves
         * in plain text, so nothing is decrypted.
         */
        fun appliesTo(envFile: File): Boolean {
            if (!envFile.isFile) return false
            val expected = ENV_CREDENTIALS.map { it.envVar }.toSet()
            return envFile.useLines { lines -> lines.any { VARIABLE_NAME.find(it)?.groupValues?.get(1) in expected } }
        }

        /** Decrypts [envFile] with `dotenvx get`, which finds the key in `.env.keys` or `DOTENV_PRIVATE_KEY`. */
        fun load(envFile: File): EnvCredentialStore {
            val process = try {
                ProcessBuilder("dotenvx", "get", "--format", "json", "-f", envFile.absolutePath)
                    .directory(envFile.absoluteFile.parentFile)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
            } catch (e: java.io.IOException) {
                throw IllegalStateException("dotenvx is needed to read ${envFile.name}; install it from https://dotenvx.com", e)
            }
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw IllegalStateException("dotenvx couldn't read ${envFile.name}: ${errors.trim().ifEmpty { "exit code ${process.exitValue()}" }}")
            }

            val env = Json.parseToJsonElement(output).let { it as JsonObject }
                .mapValues { (_, value) -> value.jsonPrimitive.content }
            if (ENV_CREDENTIALS.any { env[it.envVar]?.startsWith("encrypted:") == true }) {
                throw IllegalStateException(
                    "Couldn't decrypt ${envFile.name}: add its .env.keys file or set DOTENV_PRIVATE_KEY",
                )
            }
            return EnvCredentialStore(env)
        }
    }
}
