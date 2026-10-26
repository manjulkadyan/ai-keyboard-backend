package com.manjul.kbapi.ai

import com.manjul.kbapi.limits.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One scripted call: emit [script] up to index [failAt] (exclusive), then throw [error] if set. */
data class FakeCall(val script: List<GatewayEvent>, val failAt: Int? = null, val error: GatewayErrorCode? = null)

/**
 * A scripted [ModelGateway] for tests and `GATEWAY=fake` local runs. Call n plays `calls[n]`; the last
 * entry repeats. Every request is recorded in [requests].
 */
class FakeGateway private constructor(
    private val calls: List<FakeCall>,
    private val respond: ((GatewayRequest) -> List<GatewayEvent>)?,
) : ModelGateway {

    constructor(script: List<GatewayEvent>, failAt: Int? = null, error: GatewayErrorCode? = null) :
        this(listOf(FakeCall(script, failAt, error)), null)

    private val count = AtomicInteger()
    private val recorded = CopyOnWriteArrayList<GatewayRequest>()

    val requests: List<GatewayRequest> get() = recorded.toList()

    override fun stream(req: GatewayRequest): Flow<GatewayEvent> {
        recorded += req
        val n = count.getAndIncrement()
        val call = respond?.let { FakeCall(it(req)) } ?: calls[minOf(n, calls.lastIndex)]
        return flow {
            val stopAt = call.failAt ?: if (call.error != null) 0 else call.script.size
            for (event in call.script.take(stopAt)) emit(event)
            call.error?.let { throw GatewayException(it) }
        }
    }

    companion object {
        fun calls(vararg calls: FakeCall): FakeGateway = FakeGateway(calls.toList(), null)

        /** Canned replies for local runs: plain text, or JSON matching the schema. Never echoes input. */
        fun demo(): FakeGateway = FakeGateway(emptyList<FakeCall>(), respond = { req ->
            val usage = TokenUsage(input = 120, output = 40, cacheRead = 1500, cacheWrite = 0)
            val body = when {
                req.jsonSchema == null -> listOf("This is ", "a fake ", "rewrite.")
                "variants" in req.jsonSchema -> {
                    val modes = Regex("<mode>([^<]*)</mode>").find(req.user)?.groupValues?.get(1)?.split(',').orEmpty()
                    listOf(modes.joinToString(",", """{"variants":[""", "]}") { """{"mode":"$it","text":"Fake $it."}""" })
                }
                else -> listOf("""{"polished":"Fake polished text.",""", """"translation":"Fake translation.",""", """"source_lang_detected":"en"}""")
            }
            body.map { GatewayEvent.TextDelta(it) } + GatewayEvent.Done(StopReasons.END_TURN, usage)
        })
    }
}
