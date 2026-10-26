package com.manjul.kbapi.routes

import com.manjul.kbapi.ApiException
import com.manjul.kbapi.Errors
import com.manjul.kbapi.Services
import com.manjul.kbapi.ai.PromptRegistry
import com.manjul.kbapi.auth.principal
import com.manjul.kbapi.limits.RateLimiter
import com.manjul.kbapi.report.AiReport
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

@Serializable
data class ReportRequest(
    @SerialName("client_req_id") val clientReqId: String,
    @SerialName("prompt_id") val promptId: String,
    val reason: String,
    @SerialName("include_text") val includeText: Boolean,
    val text: String? = null,
) {
    override fun toString(): String = "ReportRequest(prompt=$promptId, reason=$reason, includeText=$includeText)"
}

@Serializable
data class OkResponse(val ok: Boolean = true)

object Reports {
    val REASONS = setOf("offensive", "wrong", "unsafe", "other")
    const val MAX_TEXT_CHARS = 4000
    val RETENTION: Duration = Duration.ofDays(90)

    /** Caps at [MAX_TEXT_CHARS] without splitting a surrogate pair. */
    fun cap(text: String): String {
        if (text.length <= MAX_TEXT_CHARS) return text
        val end = if (Character.isHighSurrogate(text[MAX_TEXT_CHARS - 1])) MAX_TEXT_CHARS - 1 else MAX_TEXT_CHARS
        return text.substring(0, end)
    }
}

/**
 * `POST /v1/ai/report` (TRD §11.4, PRD §10.6): stores a report of AI output for 90 days.
 * `text` is kept only when the user ticked "include the text". It does not count against quota;
 * it has its own per-uid bucket so it cannot be used to fill storage.
 */
fun Route.reportRoutes(services: Services) {
    val limiter = RateLimiter(services.config.uidPerMinute, services.clock)
    post("/v1/ai/report") {
        val body = call.receive<ReportRequest>()
        val p = call.principal
        if (body.clientReqId.isEmpty() || body.clientReqId.length > 64) throw ApiException(Errors.badRequest())
        if (body.reason !in Reports.REASONS) throw ApiException(Errors.badRequest())
        try {
            PromptRegistry.parse(body.promptId)
        } catch (_: IllegalArgumentException) {
            throw ApiException(Errors.badRequest())
        }
        val decision = limiter.tryAcquire(p.uid)
        if (!decision.allowed) throw ApiException(Errors.rateLimited(decision.retryAfterSeconds))

        val now = services.clock.instant()
        services.reports.save(
            AiReport(
                uid = p.uid,
                clientReqId = body.clientReqId,
                promptId = body.promptId,
                reason = body.reason,
                text = body.text?.takeIf { body.includeText }?.let(Reports::cap),
                createdAt = now,
                expiresAt = now.plus(Reports.RETENTION),
            ),
        )
        call.respond(OkResponse())
    }
}
