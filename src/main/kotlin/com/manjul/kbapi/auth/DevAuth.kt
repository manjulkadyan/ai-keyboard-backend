package com.manjul.kbapi.auth

import io.ktor.server.application.ApplicationCall

/**
 * Local development only (decision 6): with `AUTH_MODE=dev`, `X-Dev-Uid` stands in for both
 * App Check and the ID token. [com.manjul.kbapi.Config.fromEnv] refuses dev mode on Cloud Run.
 */
object DevAuth {
    const val HEADER = "X-Dev-Uid"
    private val ValidUid = Regex("[A-Za-z0-9_\\-]{1,128}")

    fun principal(call: ApplicationCall): Principal? =
        call.request.headers[HEADER]?.trim()?.takeIf { ValidUid.matches(it) }?.let { Principal.of(it) }
}
