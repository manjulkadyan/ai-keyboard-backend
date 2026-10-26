package com.manjul.kbapi.report

import java.time.Instant

/**
 * A user report of AI output. [text] is set only when the user ticked "include the text".
 * Kept until [expiresAt] (created + 90 days). [toString] never prints the text or the uid.
 */
data class AiReport(
    val uid: String,
    val clientReqId: String,
    val promptId: String,
    val reason: String,
    val text: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString(): String =
        "AiReport(prompt=$promptId, reason=$reason, hasText=${text != null}, expiresAt=$expiresAt)"
}

interface ReportStore {
    /** Idempotent per (uid, clientReqId): a retry overwrites the same record. */
    suspend fun save(report: AiReport)
}
