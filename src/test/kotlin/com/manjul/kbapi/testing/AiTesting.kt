package com.manjul.kbapi.testing

import com.manjul.kbapi.Config
import com.manjul.kbapi.ai.GatewayEvent
import com.manjul.kbapi.ai.ModelGateway
import com.manjul.kbapi.limits.TokenUsage
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val Usage1 = TokenUsage(input = 100, output = 20, cacheRead = 900, cacheWrite = 0)

fun done(stopReason: String = "end_turn") = GatewayEvent.Done(stopReason, Usage1)

fun text(t: String) = GatewayEvent.TextDelta(t)

fun aiServices(gateway: ModelGateway, config: Config = testConfig()) = testServices(config).copy(gateway = gateway)

data class SseFrame(val event: String, val data: JsonObject)

/** Parses an SSE body into frames. Every data line must be one JSON object. */
fun parseSse(body: String): List<SseFrame> =
    body.split("\n\n").map { it.trim('\n') }.filter { it.isNotBlank() }.map { block ->
        val lines = block.lines()
        val event = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
        val data = lines.first { it.startsWith("data: ") }.removePrefix("data: ")
        SseFrame(event, Json.parseToJsonElement(data).jsonObject)
    }

suspend fun ApplicationTestBuilder.quotaLeft(uid: String = "test-uid-1"): Int {
    val body = client.get("/v1/quota") { authed(uid) }.bodyAsText()
    return Json.parseToJsonElement(body).jsonObject["ai_left_today"]!!.jsonPrimitive.int
}

fun rewriteBody(text: String = "helo wrld", reqId: String = "r1", mode: String = "fix", extra: String = "") =
    """{"mode":"$mode","locale":"en","text":${JsonPrimitive(text)},"client_req_id":"$reqId"$extra}"""

fun polishBody(transcript: String = "um hello there", reqId: String = "p1", extra: String = "") =
    """{"transcript":${JsonPrimitive(transcript)},"target_lang":"es","client_req_id":"$reqId"$extra}"""
