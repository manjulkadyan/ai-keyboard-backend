package com.manjul.kbapi.ai

import com.manjul.kbapi.limits.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One model call. [jsonSchema] (a JSON Schema document) switches the output to structured JSON. */
data class GatewayRequest(
    val model: String,
    val system: String,
    val user: String,
    val maxTokens: Long,
    val jsonSchema: String?,
) {
    // Never print the prompt or the user turn.
    override fun toString(): String = "GatewayRequest(model=$model, maxTokens=$maxTokens, json=${jsonSchema != null})"
}

sealed interface GatewayEvent {
    /** A text delta of the answer. Thinking deltas never reach this type. */
    data class TextDelta(val text: String) : GatewayEvent {
        override fun toString(): String = "TextDelta(${text.length} chars)"
    }

    /** The end of the call: the API stop reason (`end_turn`, `max_tokens`, `refusal`, ...) and the token usage. */
    data class Done(val stopReason: String, val usage: TokenUsage) : GatewayEvent
}

enum class GatewayErrorCode { RATE_LIMITED, OVERLOADED, AUTH, BAD_REQUEST, OTHER }

/** An upstream failure. Carries the code only: no upstream message, no cause, no stack trace. */
class GatewayException(val code: GatewayErrorCode) : RuntimeException(code.name, null, false, false)

/** The model provider behind rewrite and polish. The flow throws [GatewayException] on upstream failure. */
fun interface ModelGateway {
    fun stream(req: GatewayRequest): Flow<GatewayEvent>
}

/** Used when no provider is configured (no `ANTHROPIC_API_KEY`): every call fails with [GatewayErrorCode.AUTH]. */
object UnavailableGateway : ModelGateway {
    override fun stream(req: GatewayRequest): Flow<GatewayEvent> = flow { throw GatewayException(GatewayErrorCode.AUTH) }
}

object Models {
    /** Haiku has no server-side fallback, so a refusal there is retried once on the step-up model. */
    fun isHaiku(model: String): Boolean = "haiku" in model
}

object StopReasons {
    const val END_TURN = "end_turn"
    const val MAX_TOKENS = "max_tokens"
    const val REFUSAL = "refusal"
}
