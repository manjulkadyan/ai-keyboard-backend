package com.manjul.kbapi.testing

import com.manjul.kbapi.auth.IdTokenVerifier
import com.manjul.kbapi.auth.JwksAppCheckVerifier
import com.manjul.kbapi.auth.RsaJwtVerifier
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * TEST-ONLY signing keys. They are generated in memory on every run and never written to disk,
 * so there is no key material in the repo. [otherKey] signs tokens that must be rejected.
 */
object TestKeys {
    const val PROJECT_ID = "kb-test-project"
    const val PROJECT_NUMBER = "123456789012"
    const val APP_ID = "1:123456789012:android:0000000000000000"

    /** Fixed "now" for every test that goes through [TestApp]. */
    val NOW: Instant = Instant.parse("2026-10-26T10:00:00Z")
    val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-kid-1").generate()
    val otherKey: RSAKey = RSAKeyGenerator(2048).keyID("test-kid-1").generate()

    private val jwkSource = ImmutableJWKSet<SecurityContext>(JWKSet(signingKey.toPublicJWK()))

    fun appCheckVerifier(clock: Clock = this.clock) = JwksAppCheckVerifier(jwkSource, PROJECT_NUMBER, clock)

    /** Same checks firebase-admin applies to Firebase ID tokens, against the local key. */
    fun idTokenVerifier(clock: Clock = this.clock): IdTokenVerifier {
        val jwt = RsaJwtVerifier(
            jwkSource = jwkSource,
            issuer = "https://securetoken.google.com/$PROJECT_ID",
            audience = PROJECT_ID,
            clock = clock,
        )
        return IdTokenVerifier { token -> jwt.verify(token)?.subject?.takeIf { it.isNotBlank() } }
    }

    fun appCheckToken(
        expiresIn: Duration = Duration.ofHours(1),
        audience: List<String> = listOf("projects/$PROJECT_NUMBER", "projects/$PROJECT_ID"),
        issuer: String = "https://firebaseappcheck.googleapis.com/$PROJECT_NUMBER",
        key: RSAKey = signingKey,
    ): String = sign(
        key,
        JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(APP_ID)
            .audience(audience)
            .issueTime(Date.from(NOW.minusSeconds(60)))
            .expirationTime(Date.from(NOW.plus(expiresIn)))
            .build(),
    )

    fun idToken(
        uid: String = "test-uid-1",
        expiresIn: Duration = Duration.ofHours(1),
        audience: String = PROJECT_ID,
        key: RSAKey = signingKey,
    ): String = sign(
        key,
        JWTClaimsSet.Builder()
            .issuer("https://securetoken.google.com/$PROJECT_ID")
            .subject(uid)
            .audience(audience)
            .issueTime(Date.from(NOW.minusSeconds(60)))
            .expirationTime(Date.from(NOW.plus(expiresIn)))
            .build(),
    )

    private fun sign(key: RSAKey, claims: JWTClaimsSet): String {
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).type(JOSEObjectType.JWT).build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
    }
}
