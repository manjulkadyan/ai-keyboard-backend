package com.manjul.kbapi.routes

import com.manjul.kbapi.Services
import com.manjul.kbapi.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuotaResponse(
    @SerialName("ai_left_today") val aiLeftToday: Int,
    @SerialName("resets_at") val resetsAt: String,
    val tier: String,
)

/** `GET /v1/quota`: remaining actions today for the caller's tier. Reading it spends nothing. */
fun Route.quotaRoutes(services: Services) {
    get("/v1/quota") {
        val s = services.limits.quota.status(call.principal)
        call.respond(QuotaResponse(s.aiLeftToday, s.resetsAt.toString(), s.tier.name))
    }
}
