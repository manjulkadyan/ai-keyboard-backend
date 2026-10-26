package com.manjul.kbapi.routes

import com.manjul.kbapi.Services
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

@Serializable
data class QuotaResponse(
    @SerialName("ai_left_today") val aiLeftToday: Int,
    @SerialName("resets_at") val resetsAt: String,
    val tier: String,
)

/**
 * `GET /v1/quota`. STUB until Task 6: everyone is FREE with the full daily allowance;
 * Task 6 replaces this with `QuotaService.status`.
 */
fun Route.quotaRoutes(services: Services) {
    get("/v1/quota") {
        val today = LocalDate.now(services.clock.withZone(ZoneOffset.UTC))
        val resetsAt = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        call.respond(QuotaResponse(services.config.freeDaily, resetsAt.toString(), "FREE"))
    }
}
