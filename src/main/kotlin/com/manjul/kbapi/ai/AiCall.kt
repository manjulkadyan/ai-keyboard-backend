package com.manjul.kbapi.ai

import com.manjul.kbapi.LogField
import com.manjul.kbapi.auth.principal
import com.manjul.kbapi.limits.Admission
import com.manjul.kbapi.limits.Limits
import com.manjul.kbapi.limits.clientIp
import com.manjul.kbapi.logField
import io.ktor.server.application.ApplicationCall
import com.manjul.kbapi.limits.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The per-request plumbing shared by rewrite and polish: preflight and refund, spend recording and the
 * access-log fields (`prompt_id@version`, model, token counts; never text).
 */
class AiCall(
    private val call: ApplicationCall,
    private val limits: Limits,
    prompt: Prompt,
    model: String,
    private val trustedProxyHops: Int,
) : CallHooks {
    private var admission: Admission? = null
    private var input = 0L
    private var output = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L

    /** The running call's usage as of `message_start`, and the text chars it has produced since. */
    private var partial: TokenUsage? = null
    private var partialChars = 0L

    init {
        call.logField(LogField.PROMPT_ID, prompt.ref)
        call.logField(LogField.MODEL, model)
    }

    /** Preflight in Task 6 order; throws `ApiException` (503/429/400/402). */
    suspend fun admit(inputChars: Int, clientReqId: String): Admission =
        limits.preflight.admit(call.principal, call.clientIp(trustedProxyHops), inputChars, clientReqId).also { admission = it }

    /**
     * Gives the action back when nothing reached the client. A retry of an already counted
     * `client_req_id` refunds nothing, so it cannot undo the original action (its attempt stays used).
     */
    suspend fun refund(clientReqId: String) {
        val a = admission ?: return
        if (a.duplicate) return
        limits.quota.refund(call.principal, clientReqId, a.reservedDay)
    }

    override fun onUsage(model: String, usage: TokenUsage) {
        partial = usage
        partialChars = 0
    }

    override fun onText(chars: Int) {
        partialChars += chars
    }

    /**
     * A call that never reached `Done` was still billed for its input and the output generated so far,
     * so the spend breaker (TRD §4.4.4) counts it: `message_start` usage, with output estimated from the
     * text relayed (thinking tokens are not visible and are not counted).
     */
    override suspend fun onUnfinished(model: String) {
        val u = partial ?: return
        partial = null
        val estimatedOutput = (partialChars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
        val usage = u.copy(output = maxOf(u.output, estimatedOutput))
        withContext(NonCancellable) { addUsage(model, usage) }
    }

    override suspend fun onDone(model: String, done: GatewayEvent.Done) {
        partial = null
        addUsage(model, done.usage)
    }

    private suspend fun addUsage(model: String, u: TokenUsage) {
        input += u.input
        output += u.output
        cacheRead += u.cacheRead
        cacheWrite += u.cacheWrite
        call.logField(LogField.MODEL, model)
        call.logField(LogField.INPUT_TOKENS, input)
        call.logField(LogField.OUTPUT_TOKENS, output)
        call.logField(LogField.CACHE_READ_TOKENS, cacheRead)
        call.logField(LogField.CACHE_WRITE_TOKENS, cacheWrite)
        try {
            limits.spend.record(model, u)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A spend-store outage must not fail a call that already ran; the breaker reads the store later.
        }
    }

    override fun onError(code: GatewayErrorCode) {
        call.logField(LogField.ERROR_CODE, "upstream_" + code.name.lowercase())
    }

    private companion object {
        /** A rough chars-per-token ratio for estimating the output of an unfinished call. */
        const val CHARS_PER_TOKEN = 4L
    }
}

/** Parses model JSON without letting a parser message (which quotes the input) escape. */
internal fun parseObjectOrNull(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun JsonElement?.stringOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
