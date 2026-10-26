package com.manjul.kbapi.routes

import com.manjul.kbapi.ai.RewriteService
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RewriteRequest(
    val mode: String,
    val locale: String,
    val text: String,
    @SerialName("client_req_id") val clientReqId: String,
    @SerialName("target_lang") val targetLang: String? = null,
    val modes: List<String>? = null,
) {
    // Never print the user's text.
    override fun toString(): String = "RewriteRequest(mode=$mode, modes=$modes, chars=${text.length})"
}

fun Route.rewriteRoutes(service: RewriteService) {
    post("/v1/rewrite") {
        service.handle(call, call.receive<RewriteRequest>())
    }
}
