package com.manjul.kbapi.ai

import com.manjul.kbapi.limits.MAX_ATTEMPTS_PER_REQ_ID
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.aiServices
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.done
import com.manjul.kbapi.testing.parseSse
import com.manjul.kbapi.testing.quotaLeft
import com.manjul.kbapi.testing.rewriteBody
import com.manjul.kbapi.testing.testApp
import com.manjul.kbapi.testing.testConfig
import com.manjul.kbapi.testing.text
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewriteRouteTest {
    private val prompts = PromptRegistry()

    private fun err(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content

    @Test
    fun `deltas are relayed in order then done`() {
        val gateway = FakeGateway(listOf(text("Hello"), text(", "), text("world."), done()))
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Type"]!!.startsWith("text/event-stream"))
            val frames = parseSse(body)
            assertEquals(listOf("delta", "delta", "delta", "done"), frames.map { it.event })
            assertEquals("Hello, world.", frames.dropLast(1).joinToString("") { it.data["text"]!!.jsonPrimitive.content })
            val doneData = frames.last().data
            assertEquals(9, doneData["quota_left"]!!.jsonPrimitive.int)
            assertEquals(false, doneData["trimmed"]!!.jsonPrimitive.boolean)
            assertEquals("rewrite@1", doneData["prompt"]!!.jsonPrimitive.content)
            assertEquals(9, quotaLeft())

            val req = gateway.requests.single()
            assertEquals("claude-haiku-5-5", req.model)
            assertEquals(1024L, req.maxTokens)
            assertEquals(prompts.get("rewrite").system, req.system)
            assertEquals("<mode>fix</mode><locale>en</locale><text>helo wrld</text>", req.user)
            assertNull(req.jsonSchema)
        }
    }

    @Test
    fun `max_tokens marks the result trimmed`() = testApp(aiServices(FakeGateway(listOf(text("Part"), done("max_tokens"))))) {
        val (_, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
        assertEquals(true, parseSse(body).last().data["trimmed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `input over 4000 chars is too_long and uses no quota`() {
        val gateway = FakeGateway(listOf(text("x"), done()))
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody("a".repeat(4001))) { authed() }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("too_long", err(body))
            assertEquals(10, quotaLeft())
            assertTrue(gateway.requests.isEmpty())
        }
    }

    @Test
    fun `upstream error before the first delta is 503 and refunds`() {
        for (code in listOf(GatewayErrorCode.OVERLOADED, GatewayErrorCode.RATE_LIMITED)) {
            testApp(aiServices(FakeGateway(listOf(text("never")), failAt = 0, error = code))) {
                val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertEquals("overloaded", err(body))
                assertEquals("5", response.headers["Retry-After"])
                assertEquals(10, quotaLeft())
            }
        }
    }

    @Test
    fun `other upstream error before the first delta is 500 and refunds`() {
        testApp(aiServices(FakeGateway(emptyList(), failAt = 0, error = GatewayErrorCode.AUTH))) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("internal", err(body))
            assertEquals(10, quotaLeft())
        }
    }

    @Test
    fun `a call that ends with no text is 500 internal and refunds`() {
        for (stop in listOf("end_turn", "max_tokens")) {
            for (extra in listOf("", ""","modes":["formal","friendly"]""")) {
                testApp(aiServices(FakeGateway(listOf(done(stop))))) {
                    val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(extra = extra)) { authed() }
                    assertEquals(HttpStatusCode.InternalServerError, response.status)
                    assertEquals("internal", err(body))
                    assertEquals(10, quotaLeft())
                }
            }
        }
    }

    @Test
    fun `error after the first delta is an error event and keeps the quota`() {
        val gateway = FakeGateway(listOf(text("Hel"), text("lo"), done()), failAt = 1, error = GatewayErrorCode.OVERLOADED)
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            val frames = parseSse(body)
            assertEquals(listOf("delta", "error"), frames.map { it.event })
            assertEquals("overloaded", frames.last().data["error"]!!.jsonPrimitive.content)
            assertEquals(9, quotaLeft())
        }
    }

    @Test
    fun `haiku refusal steps up and the client sees only the step-up text`() {
        val gateway = FakeGateway.calls(
            FakeCall(listOf(done("refusal"))),
            FakeCall(listOf(text("Hola"), text("."), done())),
        )
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            val frames = parseSse(body)
            assertEquals("Hola.", frames.filter { it.event == "delta" }.joinToString("") { it.data["text"]!!.jsonPrimitive.content })
            assertEquals("done", frames.last().event)
            assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), gateway.requests.map { it.model })
            assertEquals(gateway.requests[0].user, gateway.requests[1].user)
            assertEquals(9, quotaLeft())
        }
    }

    @Test
    fun `refusal on both models is 451 with a refund`() {
        val gateway = FakeGateway(listOf(done("refusal")))
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertEquals(HttpStatusCode(451, "Unavailable For Legal Reasons").value, response.status.value)
            assertEquals("refused", err(body))
            assertEquals(2, gateway.requests.size)
            assertEquals(10, quotaLeft())
        }
    }

    @Test
    fun `sonnet as primary does not step up again`() {
        val gateway = FakeGateway(listOf(done("refusal")))
        testApp(aiServices(gateway, testConfig().copy(rewriteModel = "claude-sonnet-5-5"))) {
            val (response, _) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertEquals(451, response.status.value)
            assertEquals(listOf("claude-sonnet-5-5"), gateway.requests.map { it.model })
        }
    }

    @Test
    fun `multiple modes return JSON variants and count once`() {
        val gateway = FakeGateway(
            listOf(
                text("""{"variants":[{"mode":"formal","text":"Good day."},"""),
                text("""{"mode":"friendly","text":"Hey there!"}]}"""),
                done(),
            ),
        )
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(
                HttpMethod.Post, "/v1/rewrite", rewriteBody(extra = ""","modes":["formal","friendly"]"""),
            ) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Type"]!!.startsWith("application/json"))
            val json = Json.parseToJsonElement(body).jsonObject
            val variants = json["variants"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("formal", "friendly"), variants.map { it["mode"]!!.jsonPrimitive.content })
            assertEquals("Hey there!", variants[1]["text"]!!.jsonPrimitive.content)
            assertEquals(9, json["quota_left"]!!.jsonPrimitive.int)
            assertEquals(9, quotaLeft())
            val req = gateway.requests.single()
            assertEquals(prompts.get("multi_tone").system, req.system)
            assertNotNull(req.jsonSchema)
            assertTrue("<mode>formal,friendly</mode>" in req.user)
        }
    }

    @Test
    fun `one mode in modes is bad_request`() = testApp(aiServices(FakeGateway(listOf(done())))) {
        val (response, body) = contractCall(
            HttpMethod.Post, "/v1/rewrite", rewriteBody(extra = ""","modes":["formal"]"""), requestMustBeValid = false,
        ) { authed() }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("bad_request", err(body))
    }

    @Test
    fun `translate without target_lang is bad_request`() = testApp(aiServices(FakeGateway(listOf(done())))) {
        val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(mode = "translate")) { authed() }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("bad_request", err(body))
        assertEquals(10, quotaLeft())
    }

    @Test
    fun `translate passes the target in the user turn`() {
        val gateway = FakeGateway(listOf(text("Hola"), done()))
        testApp(aiServices(gateway)) {
            val (response, _) = contractCall(
                HttpMethod.Post, "/v1/rewrite", rewriteBody(mode = "translate", extra = ""","target_lang":"es""""),
            ) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue("<target_lang>es</target_lang>" in gateway.requests.single().user)
        }
    }

    @Test
    fun `exhausted quota is 402 with resets_at`() {
        val gateway = FakeGateway(listOf(text("ok"), done()))
        testApp(aiServices(gateway, testConfig().copy(freeDaily = 1))) {
            contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "q1")) { authed() }
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "q2")) { authed() }
            assertEquals(HttpStatusCode.PaymentRequired, response.status)
            assertEquals("quota", err(body))
            assertEquals("2026-10-27T00:00:00Z", Json.parseToJsonElement(body).jsonObject["resets_at"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a retry with the same client_req_id counts once`() {
        val gateway = FakeGateway(listOf(text("ok"), done()))
        testApp(aiServices(gateway)) {
            contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "same")) { authed() }
            val (_, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "same")) { authed() }
            assertEquals(9, parseSse(body).last().data["quota_left"]!!.jsonPrimitive.int)
            assertEquals(9, quotaLeft())
        }
    }

    @Test
    fun `replaying a client_req_id cannot buy model calls past the quota`() {
        val gateway = FakeGateway(listOf(text("ok"), done()))
        testApp(aiServices(gateway, testConfig().copy(freeDaily = 1))) {
            repeat(MAX_ATTEMPTS_PER_REQ_ID) {
                val (r, _) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "replay")) { authed() }
                assertEquals(HttpStatusCode.OK, r.status)
            }
            repeat(3) {
                val (r, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "replay")) { authed() }
                assertEquals(HttpStatusCode.PaymentRequired, r.status)
                assertEquals("quota", err(body))
            }
            assertEquals(MAX_ATTEMPTS_PER_REQ_ID, gateway.requests.size)
            assertEquals(0, quotaLeft())
        }
    }

    @Test
    fun `the 11th call within a minute is 429 with Retry-After`() {
        val gateway = FakeGateway(listOf(text("ok"), done()))
        testApp(aiServices(gateway, testConfig().copy(freeDaily = 100))) {
            repeat(10) { i ->
                val (r, _) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "rl$i")) { authed() }
                assertEquals(HttpStatusCode.OK, r.status)
            }
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody(reqId = "rl10")) { authed() }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("rate_limited", err(body))
            assertNotNull(response.headers["Retry-After"]?.toLongOrNull())
        }
    }

    @Test
    fun `kill switch is 503 ai_paused`() {
        val gateway = FakeGateway(listOf(text("ok"), done()))
        testApp(aiServices(gateway, testConfig().copy(aiEnabled = false))) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("ai_paused", err(body))
            assertTrue(gateway.requests.isEmpty())
        }
    }

    @Test
    fun `malformed body is bad_request`() = testApp(aiServices(FakeGateway(listOf(done())))) {
        val (response, body) = contractCall(
            HttpMethod.Post, "/v1/rewrite", """{"mode":"fix","locale":"en"}""", requestMustBeValid = false,
        ) { authed() }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("bad_request", err(body))
    }

    @Test
    fun `completed calls record spend`() {
        val gateway = FakeGateway(listOf(text("ok"), done()))
        val services = aiServices(gateway)
        testApp(services) {
            contractCall(HttpMethod.Post, "/v1/rewrite", rewriteBody()) { authed() }
            assertTrue(services.limits.spend.rollingHourUsd() > 0.0)
        }
    }
}
