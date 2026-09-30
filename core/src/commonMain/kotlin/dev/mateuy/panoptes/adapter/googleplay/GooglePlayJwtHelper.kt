package dev.mateuy.panoptes.adapter.googleplay

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object GooglePlayJwtHelper {
    fun createJwt(clientEmail: String, privateKeyPem: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 3600

        val header = base64url("""{"alg":"RS256","typ":"JWT"}""")
        val claims = base64url(
            """{"iss":"$clientEmail","scope":"https://www.googleapis.com/auth/androidpublisher","aud":"https://oauth2.googleapis.com/token","exp":$exp,"iat":$now}"""
        )
        val signingInput = "$header.$claims"

        val pemStripped = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(pemStripped)
        val rsaKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(rsaKey)
            update(signingInput.toByteArray())
        }.sign()

        return "$signingInput.${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
    }

    private fun base64url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
