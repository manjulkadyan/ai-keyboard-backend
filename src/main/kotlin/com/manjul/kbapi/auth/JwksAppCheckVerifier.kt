package com.manjul.kbapi.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier
import java.net.URI
import java.net.URL
import java.time.Clock
import java.time.Duration

/**
 * Verifies a Firebase App Check token (TRD §5.2):
 * RS256 signature against the App Check JWKS, issuer `https://firebaseappcheck.googleapis.com/<projectNumber>`,
 * audience containing `projects/<projectNumber>`, not expired.
 */
class JwksAppCheckVerifier(
    jwkSource: JWKSource<SecurityContext>,
    projectNumber: String,
    clock: Clock,
) : AppCheckVerifier {

    constructor(jwksUrl: URL, projectNumber: String, clock: Clock) :
        this(JWKSourceBuilder.create<SecurityContext>(jwksUrl).build(), projectNumber, clock)

    private val jwt = RsaJwtVerifier(
        jwkSource = jwkSource,
        issuer = "https://firebaseappcheck.googleapis.com/$projectNumber",
        audience = "projects/$projectNumber",
        clock = clock,
    )

    override fun verify(token: String): Boolean = jwt.verify(token) != null

    companion object {
        val DEFAULT_JWKS_URL: URL = URI("https://firebaseappcheck.googleapis.com/v1/jwks").toURL()
    }
}

/**
 * RS256 JWT check with an injected clock. Returns the claims when the signature, issuer, audience
 * and expiry hold, else null. Exceptions are swallowed on purpose: their messages may quote the token.
 */
class RsaJwtVerifier(
    jwkSource: JWKSource<SecurityContext>,
    private val issuer: String,
    private val audience: String,
    private val clock: Clock,
    private val issuedAtSkew: Duration = Duration.ofSeconds(60),
) {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
        // Time and claim checks run below against the injected clock.
        jwtClaimsSetVerifier = JWTClaimsSetVerifier { _, _ -> }
    }

    fun verify(token: String): JWTClaimsSet? {
        if (token.isBlank() || token.length > MAX_TOKEN_CHARS) return null
        val claims = try {
            processor.process(token, null)
        } catch (_: Exception) {
            return null
        }
        if (claims.issuer != issuer) return null
        if (audience !in claims.audience.orEmpty()) return null
        val now = clock.instant()
        val expiresAt = claims.expirationTime?.toInstant() ?: return null
        if (!now.isBefore(expiresAt)) return null
        val issuedAt = claims.issueTime?.toInstant()
        if (issuedAt != null && issuedAt.isAfter(now.plus(issuedAtSkew))) return null
        val notBefore = claims.notBeforeTime?.toInstant()
        if (notBefore != null && notBefore.isAfter(now.plus(issuedAtSkew))) return null
        return claims
    }

    private companion object {
        const val MAX_TOKEN_CHARS = 8192
    }
}
