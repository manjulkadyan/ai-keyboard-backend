package com.manjul.kbapi.auth

import com.manjul.kbapi.AuthMode
import com.manjul.kbapi.Errors
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val APP_CHECK_HEADER = "X-Firebase-AppCheck"

class AuthChainConfig {
    var mode: AuthMode = AuthMode.FIREBASE
    var appCheck: AppCheckVerifier = AppCheckVerifier { false }
    var idTokens: IdTokenVerifier = IdTokenVerifier { null }

    /** Every path under this prefix is authenticated, including unknown ones. */
    var protectedPrefix: String = "/v1/"
}

/**
 * TRD §5.2 auth chain on `/v1/...`:
 * 1. `X-Firebase-AppCheck` missing or invalid -> 401 `app_check`;
 * 2. `Authorization: Bearer <Firebase ID token>` missing or invalid -> 401 `auth`.
 * In [AuthMode.DEV], `X-Dev-Uid` replaces both. On success the [Principal] is stored on the call.
 */
val AuthChain = createApplicationPlugin("AuthChain", ::AuthChainConfig) {
    val config = pluginConfig
    onCall { call ->
        if (!call.request.path().startsWith(config.protectedPrefix)) return@onCall

        if (config.mode == AuthMode.DEV) {
            val principal = DevAuth.principal(call) ?: return@onCall Errors.respond(call, Errors.auth())
            call.attributes.put(PrincipalKey, principal)
            return@onCall
        }

        val appCheckToken = call.request.headers[APP_CHECK_HEADER]?.trim().orEmpty()
        val appCheckOk = appCheckToken.isNotEmpty() &&
            withContext(Dispatchers.IO) { config.appCheck.verify(appCheckToken) }
        if (!appCheckOk) return@onCall Errors.respond(call, Errors.appCheck())

        val bearer = call.request.headers["Authorization"]?.trim().orEmpty()
        val idToken = if (bearer.length > 7 && bearer.substring(0, 7).equals("Bearer ", ignoreCase = true)) {
            bearer.substring(7).trim()
        } else {
            ""
        }
        val uid = idToken.takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.IO) { config.idTokens.verify(it) } }
            ?: return@onCall Errors.respond(call, Errors.auth())
        call.attributes.put(PrincipalKey, Principal.of(uid))
    }
}
