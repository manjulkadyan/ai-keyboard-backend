package com.manjul.kbapi.routes

import com.manjul.kbapi.KbJson
import com.manjul.kbapi.LogField
import com.manjul.kbapi.Services
import com.manjul.kbapi.billing.PlayApiException
import com.manjul.kbapi.billing.PubSubPush
import com.manjul.kbapi.billing.RtdnMessage
import com.manjul.kbapi.billing.RtdnType
import com.manjul.kbapi.logField
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("kbapi.billing.rtdn")

/**
 * `POST /internal/rtdn`: the Pub/Sub push endpoint for Play Real-time Developer Notifications. It sits outside
 * `/v1/`, so the Firebase auth chain skips it and this route checks the push OIDC token itself.
 *
 * - A missing or bad token, or push auth not configured, is 401 and does no work.
 * - A message whose messageId was already processed is 204 without a Play read (at-least-once delivery).
 * - Test notifications, other packages, one-time products and undecodable data are 204 and dropped.
 * - Play down is 503, so Pub/Sub redelivers. The messageId is marked only after success.
 *
 * The access line carries `rtdn_type` only. The token, the order id and the JWT are never logged.
 */
fun Route.rtdnRoutes(services: Services) {
    post("/internal/rtdn") {
        val auth = services.pushAuth
        val header = call.request.headers["Authorization"]
        if (auth == null || !withContext(Dispatchers.IO) { auth.verify(header) }) {
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            return@post
        }
        val push = runCatching { KbJson.decodeFromString(PubSubPush.serializer(), call.receiveText()) }.getOrNull()
        val messageId = push?.message?.id
        val message = RtdnMessage.decode(push?.message?.data)
        val target = when (message) {
            is RtdnMessage.Subscription -> message.token to message.type
            is RtdnMessage.Voided -> message.token to RtdnType.VOIDED
            else -> null
        }
        target?.let { call.logField(LogField.RTDN_TYPE, it.second.toLong()) }
        if (target == null || message?.packageName != services.config.playPackageName) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        val dedupe = services.limits.rtdnDedupe
        if (messageId != null && dedupe.seen(messageId)) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        val service = services.entitlementServiceOrNull() ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        }
        try {
            service.onRtdn(target.first, target.second)
        } catch (e: PlayApiException) {
            log.error("rtdn processing failed rtdn_type={} play_status={}", target.second, e.status)
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        }
        if (messageId != null) dedupe.mark(messageId)
        call.respond(HttpStatusCode.NoContent)
    }
}
