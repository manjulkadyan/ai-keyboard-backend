package com.manjul.kbapi.ai

import com.manjul.kbapi.ApiError
import com.manjul.kbapi.ApiException
import com.manjul.kbapi.Errors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn

/** A gateway event or a failure, so upstream exceptions never cancel the caller's scope. */
sealed interface StreamItem {
    data class Event(val event: GatewayEvent) : StreamItem
    data class Failed(val code: GatewayErrorCode) : StreamItem
}

/** The state of a call once its first text delta (or its end) has arrived. */
sealed interface Opened {
    val model: String

    /** Text arrived: the HTTP status can be committed. [rest] holds the remaining items. */
    class Streaming(override val model: String, val first: String, val rest: ReceiveChannel<StreamItem>) : Opened

    /** The call ended without any text (and without a refusal). */
    class Empty(override val model: String, val done: GatewayEvent.Done) : Opened
}

/** How a stream that already sent text ended. */
sealed interface Outcome {
    data class Completed(val done: GatewayEvent.Done) : Outcome
    data class Failed(val code: GatewayErrorCode) : Outcome
}

/** Per-call hooks: [onDone] after every model call (spend and token logging), [onError] on a gateway failure. */
interface CallHooks {
    suspend fun onDone(model: String, done: GatewayEvent.Done)
    fun onError(code: GatewayErrorCode)
}

/**
 * Runs one AI action against the [ModelGateway] (TRD §4.4.2):
 * - waits for the first text delta before anything is sent, so earlier failures are real HTTP statuses;
 * - a Haiku refusal before any text is retried once on [stepUpModel]; a final refusal is 451 `refused`;
 * - upstream `RATE_LIMITED`/`OVERLOADED` before text is 503 `overloaded`; any other failure is 500 `internal`.
 * Non-gateway exceptions are treated as [GatewayErrorCode.OTHER]; their messages are never read.
 */
class AiRunner(private val gateway: ModelGateway, private val stepUpModel: String) {

    fun attempts(req: GatewayRequest): List<GatewayRequest> =
        if (Models.isHaiku(req.model) && stepUpModel != req.model) listOf(req, req.copy(model = stepUpModel)) else listOf(req)

    private fun items(scope: CoroutineScope, req: GatewayRequest): ReceiveChannel<StreamItem> =
        gateway.stream(req)
            .map<GatewayEvent, StreamItem> { StreamItem.Event(it) }
            .catch { e -> emit(StreamItem.Failed((e as? GatewayException)?.code ?: GatewayErrorCode.OTHER)) }
            .produceIn(scope)

    /** Throws [ApiException] for every failure before the first text delta. */
    suspend fun open(scope: CoroutineScope, req: GatewayRequest, hooks: CallHooks): Opened {
        val attempts = attempts(req)
        for ((index, attempt) in attempts.withIndex()) {
            val channel = items(scope, attempt)
            var opened: Opened? = null
            try {
                while (opened == null) {
                    val item = channel.receiveCatching().getOrNull() ?: fail(hooks, GatewayErrorCode.OTHER)
                    when (item) {
                        is StreamItem.Failed -> fail(hooks, item.code)
                        is StreamItem.Event -> when (val e = item.event) {
                            is GatewayEvent.TextDelta -> if (e.text.isNotEmpty()) opened = Opened.Streaming(attempt.model, e.text, channel)
                            is GatewayEvent.Done -> {
                                hooks.onDone(attempt.model, e)
                                if (e.stopReason != StopReasons.REFUSAL) {
                                    opened = Opened.Empty(attempt.model, e)
                                } else if (index == attempts.lastIndex) {
                                    throw ApiException(Errors.refused())
                                } else {
                                    break
                                }
                            }
                        }
                    }
                }
            } finally {
                if (opened !is Opened.Streaming) channel.cancel()
            }
            if (opened != null) return opened
        }
        throw ApiException(Errors.refused())
    }

    /**
     * [open] for a streamed endpoint: a call that ended without any text (an empty `end_turn`, or thinking
     * that used up `max_tokens`) delivered nothing, so it is a pre-stream 500 `internal` and the caller refunds.
     */
    suspend fun openStreaming(scope: CoroutineScope, req: GatewayRequest, hooks: CallHooks): Opened.Streaming =
        when (val opened = open(scope, req, hooks)) {
            is Opened.Streaming -> opened
            is Opened.Empty -> fail(hooks, GatewayErrorCode.OTHER)
        }

    /** Relays the rest of a stream to [onText]. Never throws for upstream failures. */
    suspend fun drain(stream: Opened.Streaming, hooks: CallHooks, onText: suspend (String) -> Unit): Outcome {
        try {
            for (item in stream.rest) {
                when (item) {
                    is StreamItem.Failed -> {
                        hooks.onError(item.code)
                        return Outcome.Failed(item.code)
                    }
                    is StreamItem.Event -> when (val e = item.event) {
                        is GatewayEvent.TextDelta -> if (e.text.isNotEmpty()) onText(e.text)
                        is GatewayEvent.Done -> {
                            hooks.onDone(stream.model, e)
                            return Outcome.Completed(e)
                        }
                    }
                }
            }
            hooks.onError(GatewayErrorCode.OTHER)
            return Outcome.Failed(GatewayErrorCode.OTHER)
        } finally {
            stream.rest.cancel()
        }
    }

    /** Runs the call to the end and returns the whole text. Every failure throws [ApiException]. */
    suspend fun collect(scope: CoroutineScope, req: GatewayRequest, hooks: CallHooks): Pair<String, GatewayEvent.Done> =
        when (val opened = open(scope, req, hooks)) {
            is Opened.Empty -> "" to opened.done
            is Opened.Streaming -> {
                val text = StringBuilder(opened.first)
                when (val outcome = drain(opened, hooks) { text.append(it) }) {
                    is Outcome.Failed -> throw ApiException(preStreamError(outcome.code))
                    is Outcome.Completed -> {
                        if (outcome.done.stopReason == StopReasons.REFUSAL) throw ApiException(Errors.refused())
                        text.toString() to outcome.done
                    }
                }
            }
        }

    private fun fail(hooks: CallHooks, code: GatewayErrorCode): Nothing {
        hooks.onError(code)
        throw ApiException(preStreamError(code))
    }

    companion object {
        fun preStreamError(code: GatewayErrorCode): ApiError = when (code) {
            GatewayErrorCode.RATE_LIMITED, GatewayErrorCode.OVERLOADED -> Errors.overloaded()
            else -> Errors.internal()
        }

        /** The `error` value of an SSE `error` event once streaming has started. */
        fun streamErrorCode(code: GatewayErrorCode): String = when (code) {
            GatewayErrorCode.RATE_LIMITED, GatewayErrorCode.OVERLOADED -> "overloaded"
            else -> "internal"
        }
    }
}
