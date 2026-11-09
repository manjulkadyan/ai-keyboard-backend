package com.manjul.kbapi.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

/** Firebase ID tokens via firebase-admin `FirebaseAuth.verifyIdToken` (signature, issuer, audience, expiry). */
class FirebaseIdTokenVerifier(private val auth: FirebaseAuth) : IdTokenVerifier {

    override fun verify(token: String): String? {
        if (token.isBlank()) return null
        return try {
            auth.verifyIdToken(token).uid?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // FirebaseAuthException messages can quote token contents; drop them.
            null
        }
    }

    companion object {
        /** Initialises the default FirebaseApp with Application Default Credentials (the Cloud Run service account). */
        fun create(projectId: String): FirebaseIdTokenVerifier = FirebaseIdTokenVerifier(firebaseAuth(projectId))

        /** The default app's [FirebaseAuth], initialising the app on first use. Shared with account deletion. */
        fun firebaseAuth(projectId: String): FirebaseAuth {
            val app = FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(projectId)
                    .build(),
            )
            return FirebaseAuth.getInstance(app)
        }
    }
}
