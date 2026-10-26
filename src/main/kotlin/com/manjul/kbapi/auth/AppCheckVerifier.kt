package com.manjul.kbapi.auth

/** Verifies an `X-Firebase-AppCheck` token. Implementations never throw and never log the token. */
fun interface AppCheckVerifier {
    fun verify(token: String): Boolean
}
