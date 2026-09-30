package dev.mateuy.panoptes.adapter.snap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ubuntu One macaroons from `snapcraft export-login` (the value of SNAPCRAFT_STORE_CREDENTIALS).
 *
 * Mirrors craft-store: the root macaroon is sent as-is and the discharge must be bound to it
 * (pymacaroons `prepare_for_request`) before building the `Authorization` header.
 */
internal class SnapCredentials(val root: String, val discharge: String) {

    fun authorizationHeader(): String = "Macaroon root=$root, discharge=${bindDischarge(root, discharge)}"

    companion object {
        /** Accepts the base64 or decoded form, in either the JSON (`{"t":"u1-macaroon",...}`) or legacy INI format. */
        fun parse(raw: String): SnapCredentials {
            val trimmed = raw.trim()
            val content = runCatching { String(Base64.getMimeDecoder().decode(trimmed)) }
                .getOrNull()
                ?.takeIf { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
                ?: trimmed

            return if (content.trimStart().startsWith("{")) {
                val obj = Json.parseToJsonElement(content).jsonObject
                val macaroons = obj["v"] as? JsonObject ?: obj
                SnapCredentials(
                    root = macaroons["r"]?.jsonPrimitive?.content ?: error("Snap credentials: missing root macaroon"),
                    discharge = macaroons["d"]?.jsonPrimitive?.content ?: error("Snap credentials: missing discharge macaroon"),
                )
            } else {
                // Legacy INI: [login.ubuntu.com] macaroon = … / unbound_discharge = …
                fun value(key: String) = content.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.substringBefore("=").trim() == key }
                    ?.substringAfter("=")?.trim()
                SnapCredentials(
                    root = value("macaroon") ?: error("Snap credentials: unrecognised format"),
                    discharge = value("unbound_discharge") ?: error("Snap credentials: missing unbound_discharge"),
                )
            }
        }

        /** Returns [discharge] re-serialized with its signature bound to [root]'s (v1 macaroons, as used by Ubuntu One). */
        internal fun bindDischarge(root: String, discharge: String): String {
            val rootSignature = signature(decode(root))
            val dischargeBytes = decode(discharge)
            val sigOffset = signatureOffset(dischargeBytes)

            val zeroKey = ByteArray(32)
            val bound = hmac(zeroKey, hmac(zeroKey, rootSignature) + hmac(zeroKey, dischargeBytes.copyOfRange(sigOffset, sigOffset + 32)))
            bound.copyInto(dischargeBytes, sigOffset)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(dischargeBytes)
        }

        private fun decode(serialized: String): ByteArray {
            val normalized = serialized.trim().replace('+', '-').replace('/', '_').trimEnd('=')
            return Base64.getUrlDecoder().decode(normalized)
        }

        private fun signature(macaroon: ByteArray): ByteArray =
            signatureOffset(macaroon).let { macaroon.copyOfRange(it, it + 32) }

        /** Offset of the 32-byte value of the `signature` packet in a v1 binary macaroon. */
        private fun signatureOffset(macaroon: ByteArray): Int {
            require(macaroon.isNotEmpty() && macaroon[0] != 2.toByte()) { "Snap credentials: only v1 macaroons are supported" }
            var pos = 0
            while (pos + 4 <= macaroon.size) {
                // Each packet: 4 hex digits of total length, then "key value\n"
                val length = String(macaroon, pos, 4, Charsets.US_ASCII).toInt(16)
                val keyEnd = (pos + 4 until pos + length).first { macaroon[it] == ' '.code.toByte() }
                val key = String(macaroon, pos + 4, keyEnd - pos - 4, Charsets.US_ASCII)
                if (key == "signature") {
                    check(length - (keyEnd + 1 - pos) - 1 == 32) { "Snap credentials: unexpected signature length" }
                    return keyEnd + 1
                }
                pos += length
            }
            error("Snap credentials: macaroon has no signature")
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }
}
