package com.manjul.kbapi.report

import com.google.cloud.Timestamp
import com.google.cloud.firestore.Firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Reports in `ai_reports/{sha256(uid:client_req_id)}`. `expires_at` is the field for the Firestore TTL
 * policy (deploy/README.md), so documents are deleted after 90 days without a job.
 */
class FirestoreReportStore(private val db: Firestore) : ReportStore {
    override suspend fun save(report: AiReport) {
        val doc = db.collection(COLLECTION).document(docId(report.uid, report.clientReqId))
        val fields = mapOf(
            "uid" to report.uid,
            "client_req_id" to report.clientReqId,
            "prompt_id" to report.promptId,
            "reason" to report.reason,
            "text" to report.text,
            "created_at" to Timestamp.ofTimeSecondsAndNanos(report.createdAt.epochSecond, report.createdAt.nano),
            "expires_at" to Timestamp.ofTimeSecondsAndNanos(report.expiresAt.epochSecond, report.expiresAt.nano),
        )
        withContext(Dispatchers.IO) { doc.set(fields).get() }
    }

    companion object {
        const val COLLECTION = "ai_reports"

        fun docId(uid: String, clientReqId: String): String =
            MessageDigest.getInstance("SHA-256").digest("$uid:$clientReqId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
