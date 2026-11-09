package com.manjul.kbapi.billing

import com.manjul.kbapi.testing.TestKeys
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PubSubOidcVerifierTest {
    private val verifier = TestKeys.pubSubVerifier()

    private fun ok(header: String?) = verifier.verify(header)

    @Test
    fun `a Pub Sub push token passes`() {
        assertTrue(ok("Bearer ${TestKeys.pubSubToken()}"))
        assertTrue(ok("bearer ${TestKeys.pubSubToken()}"))
    }

    @Test
    fun `the https issuer form also passes`() {
        assertTrue(ok("Bearer ${TestKeys.pubSubToken(issuer = "accounts.google.com")}"))
    }

    @Test
    fun `a bad audience, email, unverified email or issuer fails`() {
        assertFalse(ok("Bearer ${TestKeys.pubSubToken(audience = "https://evil.example/internal/rtdn")}"))
        assertFalse(ok("Bearer ${TestKeys.pubSubToken(email = "attacker@evil.iam.gserviceaccount.com")}"))
        assertFalse(ok("Bearer ${TestKeys.pubSubToken(emailVerified = false)}"))
        assertFalse(ok("Bearer ${TestKeys.pubSubToken(issuer = "https://securetoken.google.com/x")}"))
    }

    @Test
    fun `an expired token or another key fails`() {
        assertFalse(ok("Bearer ${TestKeys.pubSubToken(expiresIn = Duration.ofSeconds(-1))}"))
        assertFalse(ok("Bearer ${TestKeys.pubSubToken(key = TestKeys.otherKey)}"))
        assertFalse(ok("Bearer ${TestKeys.appCheckToken()}"))
    }

    @Test
    fun `a missing or malformed header fails`() {
        assertFalse(ok(null))
        assertFalse(ok(""))
        assertFalse(ok("Bearer "))
        assertFalse(ok(TestKeys.pubSubToken()))
        assertFalse(ok("Basic abc"))
    }
}
