package com.manjul.kbapi.billing

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Signs the entitlement verdict with ECDSA P-256 (`SHA256withECDSA`, base64 DER) over the decision 4 payload
 * `v1|<pro_active>|<state>|<expires_at_ms>|<issued_at_ms>`. The client checks it with `EntitlementSignature`.
 *
 * [privateKey] is a PKCS#8 EC key, as base64 DER or PEM text. It comes from the `ENTITLEMENT_SIGNING_KEY`
 * secret only: no key is committed, and tests generate one in memory. A bad key fails construction with a
 * message that never quotes the input.
 */
class EntitlementSigner(privateKey: String) {
    private val key: PrivateKey = parse(privateKey)

    fun sign(e: EntitlementRecord, issuedAtMs: Long): String = sign(e.proActive, e.state, e.expiresAtMs, issuedAtMs)

    fun sign(proActive: Boolean, state: SubState, expiresAtMs: Long, issuedAtMs: Long): String {
        val sig = Signature.getInstance(ALGORITHM).apply {
            initSign(key)
            update(payload(proActive, state, expiresAtMs, issuedAtMs).toByteArray(Charsets.UTF_8))
        }.sign()
        return Base64.getEncoder().encodeToString(sig)
    }

    companion object {
        const val ALGORITHM = "SHA256withECDSA"

        fun payload(proActive: Boolean, state: SubState, expiresAtMs: Long, issuedAtMs: Long): String =
            "v1|$proActive|${state.wire}|$expiresAtMs|$issuedAtMs"

        private fun parse(text: String): PrivateKey {
            val body = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("-----") }
                .joinToString("")
            val key = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
            } catch (_: Exception) {
                throw IllegalArgumentException("ENTITLEMENT_SIGNING_KEY is not a PKCS#8 EC private key")
            }
            require(key is ECPrivateKey && key.params.curve.field.fieldSize == 256) {
                "ENTITLEMENT_SIGNING_KEY must be an EC P-256 key"
            }
            return key
        }
    }
}
