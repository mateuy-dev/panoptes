package dev.mateuy.panoptes.adapter.appstore

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object AppStoreJwtHelper {
    fun createJwt(issuerId: String, keyId: String, privateKeyP8: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 1200 // 20 minutes

        val header = base64url("""{"alg":"ES256","kid":"$keyId","typ":"JWT"}""")
        val claims = base64url(
            """{"iss":"$issuerId","iat":$now,"exp":$exp,"aud":"appstoreconnect-v1"}"""
        )
        val signingInput = "$header.$claims"

        val pemStripped = privateKeyP8
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(pemStripped)
        val ecKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(ecKey)
            update(signingInput.toByteArray())
        }.sign()

        // Convert DER-encoded ECDSA signature to raw R||S format for JWT
        val rawSignature = derToRaw(signature)
        return "$signingInput.${Base64.getUrlEncoder().withoutPadding().encodeToString(rawSignature)}"
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        // Parse DER SEQUENCE { INTEGER r, INTEGER s }
        var offset = 2 // skip SEQUENCE tag and length
        val rLen = der[offset + 1].toInt() and 0xFF
        offset += 2
        val r = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        val sLen = der[offset + 1].toInt() and 0xFF
        offset += 2
        val s = der.copyOfRange(offset, offset + sLen)

        // Pad or trim to 32 bytes each
        fun pad32(b: ByteArray): ByteArray {
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
        return pad32(r) + pad32(s)
    }

    private fun base64url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
