package com.manjul.kbapi.routes

import com.manjul.kbapi.ApiException
import com.manjul.kbapi.Errors
import com.manjul.kbapi.Services
import com.manjul.kbapi.auth.principal
import com.manjul.kbapi.billing.RtdnMessage
import com.manjul.kbapi.billing.VerifyResult
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The client's verify body. [toString] never prints the purchase token. */
@Serializable
data class VerifyRequest(
    @SerialName("purchase_token") val purchaseToken: String,
    @SerialName("product_id") val productId: String,
) {
    override fun toString(): String = "VerifyRequest(productId=$productId)"
}

/**
 * `POST /v1/entitlement/verify` links the caller's purchase token after a fresh Play read and returns the signed
 * verdict. `GET /v1/entitlement` returns the stored verdict, re-evaluated against the clock, with no Play call.
 * The uid always comes from the verified ID token. Billing that is not configured answers 503 `unavailable`.
 */
fun Route.entitlementRoutes(services: Services) {
    post("/v1/entitlement/verify") {
        val body = call.receive<VerifyRequest>()
        if (body.purchaseToken.isBlank() || body.purchaseToken.length > RtdnMessage.MAX_TOKEN_CHARS ||
            body.productId.isBlank() || body.productId.length > 64
        ) {
            throw ApiException(Errors.badRequest())
        }
        val service = services.entitlementService()
        when (val r = service.verify(call.principal.uid, body.purchaseToken, body.productId)) {
            is VerifyResult.Ok -> call.respond(r.response)
            VerifyResult.InvalidToken -> throw ApiException(Errors.invalidToken())
            VerifyResult.Unavailable -> throw ApiException(Errors.unavailable())
        }
    }
    get("/v1/entitlement") {
        call.respond(services.entitlementService().current(call.principal.uid))
    }
}
