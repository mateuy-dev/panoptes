package dev.mateuy.panoptes.domain

import dev.mateuy.panoptes.infrastructure.CredentialStoreImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File
import java.nio.file.Files

class CredentialStoreTest {

    private fun tempFile(): String {
        return Files.createTempFile("panoptes-test", ".enc").toString()
    }

    @Test
    fun `stores and retrieves credentials`() {
        val store = CredentialStoreImpl("test-password", tempFile())
        store.set("googleplay", "packageName", "com.example.app")
        assertEquals("com.example.app", store.get("googleplay", "packageName"))
    }

    @Test
    fun `returns null for missing credential`() {
        val store = CredentialStoreImpl("test-password", tempFile())
        assertNull(store.get("googleplay", "nonexistent"))
    }

    @Test
    fun `encrypts and decrypts round-trip`() {
        val file = tempFile()
        val store1 = CredentialStoreImpl("my-secret-password", file)
        store1.set("appstore", "issuerId", "abc-123")
        store1.set("snap", "macaroon", "long-macaroon-token")
        store1.save()

        val store2 = CredentialStoreImpl("my-secret-password", file)
        store2.load()

        assertEquals("abc-123", store2.get("appstore", "issuerId"))
        assertEquals("long-macaroon-token", store2.get("snap", "macaroon"))
    }

    @Test
    fun `different password cannot decrypt`() {
        val file = tempFile()
        val store1 = CredentialStoreImpl("correct-password", file)
        store1.set("googleplay", "packageName", "com.example.app")
        store1.save()

        val store2 = CredentialStoreImpl("wrong-password", file)
        var threw = false
        try {
            store2.load()
        } catch (_: Exception) {
            threw = true
        }
        // Either throws or returns garbled data — authentication tag will fail
        // AES-GCM guarantees an exception on wrong password
        assertEquals(true, threw)
    }

    @Test
    fun `getAll returns all credentials for store`() {
        val store = CredentialStoreImpl("test-password", tempFile())
        store.set("microsoft", "tenantId", "tenant-1")
        store.set("microsoft", "clientId", "client-1")
        store.set("microsoft", "clientSecret", "secret-1")

        val all = store.getAll("microsoft")
        assertEquals(3, all.size)
        assertEquals("tenant-1", all["tenantId"])
    }
}
