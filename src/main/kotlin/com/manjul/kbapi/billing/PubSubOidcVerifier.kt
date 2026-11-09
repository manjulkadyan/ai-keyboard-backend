package com.manjul.kbapi.billing

import com.manjul.kbapi.auth.RsaJwtVerifier
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import java.net.URI
import java.net.URL
import java.time.Clock

/**
 * Authenticates a Pub/Sub push (`Authorization: Bearer <OIDC JWT>`). The token must be RS256-signed by Google
 * ([GOOGLE_JWKS_URL]), carry `aud` = [audience] (the push endpoint URL set on the subscription), and name the
 * push service account in a verified `email` claim. Google issues it as `https://accounts.google.com` or
 * `accounts.google.com`; both are accepted.
 */
class PubSubOidcVerifier(
    private val audience: String,
    private val serviceAccountEmail: String,
    jwks: JWKSource<SecurityContext>,
    clock: Clock = Clock.systemUTC(),
) {
    constructor(audience: String, serviceAccountEmail: String, jwksUrl: URL, clock: Clock = Clock.systemUTC()) :
        this(audience, serviceAccountEmail, JWKSourceBuilder.create<SecurityContext>(jwksUrl).build(), clock)

    init {
        require(audience.isNotBlank() && serviceAccountEmail.isNotBlank()) { "RTDN audience and push SA are required" }
    }

    private val verifiers = ISSUERS.map { RsaJwtVerifier(jwks, it, audience, clock) }

    /** True when [authorization] is a valid push token. Never throws and never logs the token. */
    fun verify(authorization: String?): Boolean {
        val header = authorization?.trim().orEmpty()
        if (header.length <= 7 || !header.substring(0, 7).equals("Bearer ", ignoreCase = true)) return false
        val token = header.substring(7).trim()
        if (token.isEmpty()) return false
        val claims = verifiers.firstNotNullOfOrNull { it.verify(token) } ?: return false
        val email = runCatching { claims.getStringClaim("email") }.getOrNull()
        val verified = runCatching { claims.getBooleanClaim("email_verified") }.getOrNull()
        return email == serviceAccountEmail && verified == true
    }

    companion object {
        val GOOGLE_JWKS_URL: URL = URI("https://www.googleapis.com/oauth2/v3/certs").toURL()
        private val ISSUERS = listOf("https://accounts.google.com", "accounts.google.com")
    }
}
