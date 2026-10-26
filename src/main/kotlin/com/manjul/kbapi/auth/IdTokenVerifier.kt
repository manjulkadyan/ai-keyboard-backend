package com.manjul.kbapi.auth

/** Verifies a Firebase ID token and returns its uid, or null. Never throws, never logs the token. */
fun interface IdTokenVerifier {
    fun verify(token: String): String?
}
