package com.manjul.kbapi.account

import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.manjul.kbapi.auth.Principal
import com.manjul.kbapi.limits.EntitlementStore
import com.manjul.kbapi.limits.IntegrityVerdictCache
import com.manjul.kbapi.limits.RateLimiter
import com.manjul.kbapi.limits.UsageStore
import com.manjul.kbapi.report.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** Deletes the Firebase Auth user. A user that is already gone counts as deleted. */
fun interface FirebaseUserDeleter {
    /** Throws [UserDeletionException] when Firebase cannot be reached; the caller retries. */
    suspend fun delete(uid: String)
}

/** A Firebase Auth failure. Only the error code is kept: Firebase messages can quote the uid. */
class UserDeletionException(code: String) : RuntimeException("firebase_delete_$code", null, false, false)

class FirebaseAuthUserDeleter(private val auth: FirebaseAuth) : FirebaseUserDeleter {
    override suspend fun delete(uid: String) {
        withContext(Dispatchers.IO) {
            try {
                auth.deleteUser(uid)
            } catch (e: FirebaseAuthException) {
                if (e.authErrorCode != AuthErrorCode.USER_NOT_FOUND) {
                    throw UserDeletionException(e.authErrorCode?.name ?: e.errorCode?.name ?: "unknown")
                }
            }
        }
    }
}

@Serializable
data class DeleteResult(val deleted: Boolean)

/**
 * "Delete my data" (decision 19, TRD §11.9). Removes everything kb-api keeps for [delete]'s uid: quota counters,
 * rate buckets, the entitlement link, the integrity verdict and AI reports. It then deletes the Firebase user.
 * The Play subscription itself is not canceled: only the user can do that in Play (the deletion page says so).
 *
 * Every step is idempotent, so a retry after a partial failure finishes the job. The uid always comes from the
 * verified ID token, never from the request body. [auth] is null in dev auth, which has no Firebase user.
 * The audit line carries the uid hash only, no payload.
 */
class AccountDeletionService(
    private val usage: UsageStore,
    private val entitlements: EntitlementStore,
    private val integrity: IntegrityVerdictCache,
    private val reports: ReportStore,
    private val auth: FirebaseUserDeleter?,
    private val rateLimiters: List<RateLimiter> = emptyList(),
) {
    @Suppress("UNUSED_PARAMETER") // The id makes a client retry safe to send; deletion is idempotent anyway.
    suspend fun delete(uid: String, clientReqId: String): DeleteResult {
        usage.deleteUid(uid)
        entitlements.deleteUid(uid)
        integrity.deleteUid(uid)
        reports.deleteUid(uid)
        rateLimiters.forEach { it.forget(uid) }
        auth?.delete(uid)
        audit.info("account_delete uid_hash={} firebase_user={}", Principal.hash(uid), if (auth == null) "skipped" else "deleted")
        return DeleteResult(deleted = true)
    }

    private companion object {
        val audit = LoggerFactory.getLogger("kbapi.audit")
    }
}
