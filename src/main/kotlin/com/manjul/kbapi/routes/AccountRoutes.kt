package com.manjul.kbapi.routes

import com.manjul.kbapi.ApiException
import com.manjul.kbapi.AuthMode
import com.manjul.kbapi.Errors
import com.manjul.kbapi.Services
import com.manjul.kbapi.account.AccountDeletionService
import com.manjul.kbapi.account.UserDeletionException
import com.manjul.kbapi.auth.principal
import com.manjul.kbapi.limits.RateLimiter
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class DeleteRequest(@SerialName("client_req_id") val clientReqId: String)

private val log = LoggerFactory.getLogger("kbapi.account")

/**
 * `POST /v1/account/delete` ("Delete my data", decision 19). The target is the uid of the verified ID token;
 * any uid in the body is ignored. Idempotent: a repeat answers `{deleted: true}` again.
 * Firebase mode without a user deleter answers 503 rather than claim a deletion it cannot finish.
 */
fun Route.accountRoutes(services: Services, extraLimiters: List<RateLimiter> = emptyList()) {
    val deleter = if (services.config.authMode == AuthMode.DEV) null else services.userDeleter
    val service = AccountDeletionService(
        services.limits.usage, services.limits.entitlements, services.limits.integrity, services.reports, deleter,
        rateLimiters = listOf(services.limits.uidLimiter) + extraLimiters,
    )
    post("/v1/account/delete") {
        val body = call.receive<DeleteRequest>()
        if (body.clientReqId.isEmpty() || body.clientReqId.length > 64) throw ApiException(Errors.badRequest())
        if (services.config.authMode == AuthMode.FIREBASE && deleter == null) throw ApiException(Errors.unavailable())
        val p = call.principal
        val result = try {
            service.delete(p.uid, body.clientReqId)
        } catch (e: UserDeletionException) {
            log.error("account delete failed uid_hash={} reason={}", p.uidHash, e.message)
            throw ApiException(Errors.unavailable())
        }
        call.respond(result)
    }
}
