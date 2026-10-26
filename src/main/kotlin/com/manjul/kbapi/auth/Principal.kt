package com.manjul.kbapi.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import java.security.MessageDigest

/** The authenticated caller. Only [uidHash] (SHA-256 hex, first 16 chars) is ever logged. */
data class Principal(val uid: String, val uidHash: String) {
    override fun toString(): String = "Principal(uidHash=$uidHash)"

    companion object {
        fun of(uid: String): Principal = Principal(uid, hash(uid))

        fun hash(uid: String): String =
            MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
    }
}

val PrincipalKey = AttributeKey<Principal>("kbapi.principal")

/** The caller on a `/v1/...` route. The auth chain guarantees it is set there. */
val ApplicationCall.principal: Principal get() = attributes[PrincipalKey]
