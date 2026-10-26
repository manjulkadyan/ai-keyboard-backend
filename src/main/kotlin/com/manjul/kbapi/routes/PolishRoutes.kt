package com.manjul.kbapi.routes

import com.manjul.kbapi.ai.PolishService
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PolishRequest(
    val transcript: String,
    @SerialName("target_lang") val targetLang: String,
    @SerialName("client_req_id") val clientReqId: String,
    @SerialName("source_lang") val sourceLang: String? = null,
    /** `MATCH_INPUT` (default), `ROMAN` or `NATIVE` (decision 12). */
    val script: String? = null,
) {
    // Never print the transcript.
    override fun toString(): String = "PolishRequest(target=$targetLang, script=$script, chars=${transcript.length})"
}

fun Route.polishRoutes(service: PolishService) {
    post("/v1/polish") {
        service.handle(call, call.receive<PolishRequest>())
    }
}
