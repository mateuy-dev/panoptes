package dev.mateuy.panoptes.domain

import dev.mateuy.panoptes.infrastructure.EnvCredentialStore
import dev.mateuy.panoptes.infrastructure.Project
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectTest {

    private fun tempDir(): File = Files.createTempDirectory("panoptes-project").toFile().canonicalFile

    @Test
    fun `resolves the closest parent with a panoptes folder`() {
        val root = tempDir()
        File(root, ".panoptes").mkdirs()
        val nested = File(root, "app/src").also { it.mkdirs() }

        assertEquals(root, Project.resolve(nested.path).dir)
    }

    @Test
    fun `resolves the closest parent with an env file`() {
        val root = tempDir()
        File(root, ".env").writeText("BUNDLE_ID=com.example\n")
        val nested = File(root, "app").also { it.mkdirs() }

        assertEquals(root, Project.resolve(nested.path).dir)
    }

    @Test
    fun `falls back to the start folder`() {
        val dir = tempDir()
        assertEquals(dir, Project.resolve(dir.path).dir)
    }

    @Test
    fun `env file applies only when it defines a credential variable`() {
        val dir = tempDir()
        val env = File(dir, ".env")

        env.writeText("# comment\nBUNDLE_ID=com.example\nSENTRY_DNS=\"encrypted:abc\"\n")
        assertFalse(EnvCredentialStore.appliesTo(env))

        env.writeText("BUNDLE_ID=com.example\nexport APPSTORE_KEY_ID=\"encrypted:abc\"\n")
        assertTrue(EnvCredentialStore.appliesTo(env))

        assertFalse(EnvCredentialStore.appliesTo(File(dir, "missing.env")))
    }

    @Test
    fun `env store maps variables and decodes base64 keys`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"
        val store = EnvCredentialStore(
            mapOf(
                "APPSTORE_KEY_ID" to "KEY123",
                "APPSTORE_PRIVATE_KEY_BASE64" to Base64.getEncoder().encodeToString(pem.toByteArray()),
                "UNRELATED" to "x",
            ),
        )

        assertEquals("KEY123", store.get("appstore", "keyId"))
        assertEquals(pem, store.get("appstore", "privateKey"))
        assertNull(store.get("snap", "macaroon"))
        assertTrue(store.isReadOnly)
        assertFailsWith<UnsupportedOperationException> { store.set("snap", "macaroon", "x") }
    }

    @Test
    fun `env store suggests the bundle id`() {
        val store = EnvCredentialStore(mapOf("BUNDLE_ID" to "com.example.app"))
        assertEquals("com.example.app", store.suggestedAppId("appstore"))
        assertNull(store.suggestedAppId("snap"))
        assertNull(EnvCredentialStore(emptyMap()).suggestedAppId("appstore"))
    }

    @Test
    fun `first save git-ignores the credentials`() {
        val project = Project(tempDir())
        project.encryptedCredentialStore("password").apply {
            set("snap", "macaroon", "token")
            save()
        }

        assertEquals("credentials.enc\n", File(project.panoptesDir, ".gitignore").readText())
    }

    @Test
    fun `warns when git would commit the credentials`() {
        val project = Project(tempDir())
        project.credentialsFile.apply { parentFile.mkdirs(); writeText("x") }

        // Not a git repository
        assertNull(project.credentialsGitWarning())

        git(project.dir, "init", "-q")
        assertNotNull(project.credentialsGitWarning())

        File(project.panoptesDir, ".gitignore").writeText("credentials.enc\n")
        assertNull(project.credentialsGitWarning())
    }

    private fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder("git", *args).directory(dir).inheritIO().start().waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed" }
    }
}
