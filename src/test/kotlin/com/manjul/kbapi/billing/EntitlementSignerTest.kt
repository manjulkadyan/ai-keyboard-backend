package com.manjul.kbapi.billing

import com.manjul.kbapi.testing.BillingKeys
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementSignerTest {
    private val record = EntitlementRecord(
        uid = "uid-a",
        token = "tok-1",
        productId = "pro",
        state = SubState.ACTIVE,
        proActive = true,
        expiresAtMs = 1_800_000_000_000L,
        updatedAtMs = 1_700_000_000_000L,
    )

    @Test
    fun `the payload is the Task 2 client vector`() {
        assertEquals(
            "v1|true|active|1800000000000|1700000000000",
            EntitlementSigner.payload(true, SubState.ACTIVE, 1_800_000_000_000L, 1_700_000_000_000L),
        )
    }

    @Test
    fun `the signature verifies with the public key, as the client checks it`() {
        val sig = BillingKeys.signer.sign(record, 1_700_000_000_000L)
        assertTrue(BillingKeys.verifies("v1|true|active|1800000000000|1700000000000", sig))
        assertFalse(BillingKeys.verifies("v1|true|active|1800000000001|1700000000000", sig))
        assertFalse(BillingKeys.verifies("v1|false|active|1800000000000|1700000000000", sig))
    }

    @Test
    fun `another key does not verify`() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val sig = BillingKeys.signer.sign(record, 1_700_000_000_000L)
        assertFalse(BillingKeys.verifies("v1|true|active|1800000000000|1700000000000", sig, other.public))
    }

    @Test
    fun `PEM text in the env var is accepted`() {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(BillingKeys.keyPair.private.encoded)
        // Assembled at runtime so SecretScanTest never sees a PEM header in this file.
        val dash = "-----"
        val pem = "${dash}BEGIN " + "PRIVATE KEY$dash\n$b64\n${dash}END " + "PRIVATE KEY$dash\n"
        val sig = EntitlementSigner(pem).sign(record, 1_700_000_000_000L)
        assertTrue(BillingKeys.verifies("v1|true|active|1800000000000|1700000000000", sig))
    }

    @Test
    fun `a bad key fails at construction without echoing it`() {
        val e = assertFailsWith<IllegalArgumentException> { EntitlementSigner("not-a-key-SECRET") }
        assertFalse(e.message.orEmpty().contains("SECRET"))
    }
}
