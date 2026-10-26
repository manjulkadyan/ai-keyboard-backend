package com.manjul.kbapi

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** One error response: `{"error": code, ...extra}` plus optional headers. Never carries request text. */
class ApiError(
    val status: HttpStatusCode,
    val code: String,
    val extra: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) {
    fun body(): String = JsonObject(mapOf("error" to JsonPrimitive(code)) + extra).toString()
}

/**
 * Thrown from handlers to produce an [ApiError]. The message is the error code only and the
 * stack trace is not captured, so nothing request-derived can ride along.
 */
class ApiException(val error: ApiError) : RuntimeException(error.code, null, false, false)

internal val ErrorClassKey = AttributeKey<String>("kbapi.error_class")

object Errors {
    val UnavailableForLegalReasons = HttpStatusCode(451, "Unavailable For Legal Reasons")

    fun tooLong() = ApiError(HttpStatusCode.BadRequest, "too_long")
    fun badRequest() = ApiError(HttpStatusCode.BadRequest, "bad_request")
    fun appCheck() = ApiError(HttpStatusCode.Unauthorized, "app_check")
    fun auth() = ApiError(HttpStatusCode.Unauthorized, "auth")
    fun quota(resetsAt: Instant) =
        ApiError(HttpStatusCode.PaymentRequired, "quota", mapOf("resets_at" to JsonPrimitive(resetsAt.toString())))
    fun rateLimited(retryAfterSeconds: Long) = ApiError(
        HttpStatusCode.TooManyRequests,
        "rate_limited",
        mapOf("retry_after" to JsonPrimitive(retryAfterSeconds)),
        mapOf("Retry-After" to retryAfterSeconds.toString()),
    )
    fun refused() = ApiError(UnavailableForLegalReasons, "refused")
    fun aiPaused() = ApiError(HttpStatusCode.ServiceUnavailable, "ai_paused")

    /** Upstream rate limit or overload before any text was sent; the action is refunded. */
    fun overloaded(retryAfterSeconds: Long = 5) = ApiError(
        HttpStatusCode.ServiceUnavailable,
        "overloaded",
        mapOf("retry_after" to JsonPrimitive(retryAfterSeconds)),
        mapOf("Retry-After" to retryAfterSeconds.toString()),
    )
    fun internal() = ApiError(HttpStatusCode.InternalServerError, "internal")
    fun notFound() = ApiError(HttpStatusCode.NotFound, "not_found")
    fun methodNotAllowed() = ApiError(HttpStatusCode.MethodNotAllowed, "method_not_allowed")

    suspend fun respond(call: ApplicationCall, error: ApiError) {
        error.headers.forEach { (name, value) -> call.response.header(name, value) }
        call.respondText(error.body(), ContentType.Application.Json, error.status)
    }

    /**
     * Maps every failure to the error model. Parser and exception messages are dropped; only the
     * exception class name is kept, for the access log.
     */
    fun install(app: Application) {
        app.install(StatusPages) {
            exception<ApiException> { call, cause -> respond(call, cause.error) }
            exception<BadRequestException> { call, cause ->
                call.attributes.put(ErrorClassKey, cause::class.java.name)
                respond(call, badRequest())
            }
            exception<CannotTransformContentToTypeException> { call, cause ->
                call.attributes.put(ErrorClassKey, cause::class.java.name)
                respond(call, badRequest())
            }
            exception<UnsupportedMediaTypeException> { call, cause ->
                call.attributes.put(ErrorClassKey, cause::class.java.name)
                respond(call, badRequest())
            }
            exception<SerializationException> { call, cause ->
                call.attributes.put(ErrorClassKey, cause::class.java.name)
                respond(call, badRequest())
            }
            exception<Throwable> { call, cause ->
                call.attributes.put(ErrorClassKey, cause::class.java.name)
                respond(call, internal())
            }
            status(HttpStatusCode.NotFound) { call, _ -> respond(call, notFound()) }
            status(HttpStatusCode.MethodNotAllowed) { call, _ -> respond(call, methodNotAllowed()) }
        }
    }
}
