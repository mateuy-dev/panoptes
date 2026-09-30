package dev.mateuy.panoptes.infrastructure

import dev.mateuy.panoptes.domain.port.CredentialStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CredentialStoreImpl(
    private val masterPassword: String,
    private val filePath: String,
    /** Runs after every save, e.g. to keep the file git-ignored. */
    private val onSave: () -> Unit = {},
) : CredentialStore {

    private val data: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    private val json = Json { prettyPrint = false }

    companion object {
        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val PBKDF2_ITERATIONS = 310_000
        private const val KEY_SIZE = 256
    }

    override fun get(store: String, key: String): String? = data[store]?.get(key)

    override fun set(store: String, key: String, value: String) {
        data.getOrPut(store) { mutableMapOf() }[key] = value
    }

    override fun getAll(store: String): Map<String, String> = data[store]?.toMap() ?: emptyMap()

    override fun save() {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        val plaintext = json.encodeToString(
            data.mapValues { it.value.toMap() }
        ).toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(plaintext)
        file.writeBytes(encrypted)
        onSave()
    }

    override fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        val decrypted = decrypt(file.readBytes())
        val loaded = json.decodeFromString<Map<String, Map<String, String>>>(
            decrypted.toString(Charsets.UTF_8)
        )
        data.clear()
        loaded.forEach { (store, creds) ->
            data[store] = creds.toMutableMap()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(masterPassword, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return salt + iv + ciphertext
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val salt = data.copyOfRange(0, SALT_SIZE)
        val iv = data.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val ciphertext = data.copyOfRange(SALT_SIZE + IV_SIZE, data.size)
        val key = deriveKey(masterPassword, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
