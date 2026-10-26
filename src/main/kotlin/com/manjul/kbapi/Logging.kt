package com.manjul.kbapi

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.manjul.kbapi.auth.PrincipalKey
import org.slf4j.LoggerFactory

const val ACCESS_LOGGER = "kbapi.access"

/**
 * The only fields a log line may carry (TRD §5.4, PC-10). Request and response text, headers,
 * query strings, tokens, raw uids and exception messages are never logged.
 */
enum class LogField(val key: String) {
    REQUEST_ID("request_id"),
    UID_HASH("uid_hash"),
    METHOD("method"),
    ROUTE("route"),
    STATUS("status"),
    LATENCY_MS("latency_ms"),
    ERROR_CLASS("error_class"),
    ERROR_CODE("error_code"),
    PROMPT_ID("prompt_id"),
    MODEL("model"),
    INPUT_TOKENS("input_tokens"),
    OUTPUT_TOKENS("output_tokens"),
    CACHE_READ_TOKENS("cache_read_tokens"),
    CACHE_WRITE_TOKENS("cache_write_tokens"),
}

/** Route labels that may be logged. Anything else is logged as `other` so a path never carries text. */
object KnownRoutes {
    val paths = setOf("/healthz", "/v1/quota", "/v1/rewrite", "/v1/polish", "/v1/ai/report")

    fun label(path: String): String = if (path in paths) path else "other"
}

private val ExtraFieldsKey = AttributeKey<MutableMap<LogField, JsonPrimitive>>("kbapi.log_fields")
private val StartNanosKey = AttributeKey<Long>("kbapi.start_nanos")
private val SafeValue = Regex("[A-Za-z0-9_.@:\\-]{1,64}")

private fun ApplicationCall.extraFields() = attributes.computeIfAbsent(ExtraFieldsKey) { mutableMapOf() }

/** Adds a numeric field to this call's access line. */
fun ApplicationCall.logField(field: LogField, value: Long) {
    extraFields()[field] = JsonPrimitive(value)
}

/**
 * Adds a string field to this call's access line. Only short identifier-shaped values are kept
 * (model ids, `prompt_id@version`); anything else is replaced by `invalid`.
 */
fun ApplicationCall.logField(field: LogField, value: String) {
    extraFields()[field] = JsonPrimitive(if (SafeValue.matches(value)) value else "invalid")
}

/** One JSON access line per call, built from [LogField] only. */
val AccessLog = createApplicationPlugin("AccessLog") {
    val logger = LoggerFactory.getLogger(ACCESS_LOGGER)
    onCall { call -> call.attributes.put(StartNanosKey, System.nanoTime()) }
    on(ResponseSent) { call ->
        val start = call.attributes.getOrNull(StartNanosKey) ?: System.nanoTime()
        val fields = linkedMapOf<String, JsonPrimitive>(
            LogField.REQUEST_ID.key to JsonPrimitive(call.attributes.getOrNull(RequestIdKey) ?: "-"),
            LogField.UID_HASH.key to JsonPrimitive(call.attributes.getOrNull(PrincipalKey)?.uidHash ?: "-"),
            LogField.METHOD.key to JsonPrimitive(call.request.httpMethod.value.takeIf { SafeValue.matches(it) } ?: "other"),
            LogField.ROUTE.key to JsonPrimitive(KnownRoutes.label(call.request.path())),
            LogField.STATUS.key to JsonPrimitive(call.response.status()?.value ?: 0),
            LogField.LATENCY_MS.key to JsonPrimitive((System.nanoTime() - start) / 1_000_000),
        )
        call.attributes.getOrNull(ErrorClassKey)?.let { fields[LogField.ERROR_CLASS.key] = JsonPrimitive(it) }
        call.attributes.getOrNull(ExtraFieldsKey)?.forEach { (k, v) -> fields[k.key] = v }
        logger.info(JsonObject(fields).toString())
    }
}
