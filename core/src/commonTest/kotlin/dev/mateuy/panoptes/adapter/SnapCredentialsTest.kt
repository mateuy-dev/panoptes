package dev.mateuy.panoptes.adapter

import dev.mateuy.panoptes.adapter.snap.SnapCredentials
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapCredentialsTest {

    // Generated with pymacaroons 0.13.0: root.prepare_for_request(discharge).serialize()
    private val root = "MDAxZWxvY2F0aW9uIGFwaS5zbmFwY3JhZnQuaW8KMDAxN2lkZW50aWZpZXIgcm9vdC1pZAowMDE3Y2lkIHNuYXAtaWRzID0gYWJjCjAwMmZzaWduYXR1cmUgfxwRXebAUHi-SBpszNr2np9IkZKxDjh8QQ23F593E8kK"
    private val discharge = "MDAxZWxvY2F0aW9uIGxvZ2luLnVidW50dS5jb20KMDAxY2lkZW50aWZpZXIgdHAtY2F2ZWF0LWlkCjAwMTdjaWQgZXhwaXJlcyA9IDIwMzAKMDAyZnNpZ25hdHVyZSDgSSg4CuNQgTBb2O8fvweOLKFEUWyzyYSdEE16XQgq8wo"
    private val bound = "MDAxZWxvY2F0aW9uIGxvZ2luLnVidW50dS5jb20KMDAxY2lkZW50aWZpZXIgdHAtY2F2ZWF0LWlkCjAwMTdjaWQgZXhwaXJlcyA9IDIwMzAKMDAyZnNpZ25hdHVyZSAN_-4Ra9xitkQq1U3m4IxRHUfFS0mhnxzV9xhZXQGZzQo"

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun `binds discharge like pymacaroons`() {
        assertEquals(bound, SnapCredentials.bindDischarge(root, discharge))
    }

    @Test
    fun `parses base64 JSON export-login format`() {
        val creds = SnapCredentials.parse(b64("""{"t":"u1-macaroon","v":{"r":"$root","d":"$discharge"}}"""))
        assertEquals("Macaroon root=$root, discharge=$bound", creds.authorizationHeader())
    }

    @Test
    fun `parses decoded JSON format`() {
        val creds = SnapCredentials.parse("""{"r":"$root","d":"$discharge"}""")
        assertEquals(root, creds.root)
        assertEquals(discharge, creds.discharge)
    }

    @Test
    fun `parses legacy INI format`() {
        val ini = "[login.ubuntu.com]\nmacaroon = $root\nunbound_discharge = $discharge\nemail = a@b.c\n"
        val creds = SnapCredentials.parse(b64(ini))
        assertEquals(root, creds.root)
        assertEquals(discharge, creds.discharge)
    }
}
