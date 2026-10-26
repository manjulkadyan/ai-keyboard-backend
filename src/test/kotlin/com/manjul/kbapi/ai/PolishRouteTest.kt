package com.manjul.kbapi.ai

import com.manjul.kbapi.testing.aiServices
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.done
import com.manjul.kbapi.testing.parseSse
import com.manjul.kbapi.testing.polishBody
import com.manjul.kbapi.testing.quotaLeft
import com.manjul.kbapi.testing.testApp
import com.manjul.kbapi.testing.text
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolishRouteTest {
    private val json = """{"polished":"Hello there.","translation":"Hola.","source_lang_detected":"en"}"""

    @Test
    fun `raw json streams as deltas and done carries the parsed fields`() {
        val gateway = FakeGateway(listOf(text(json.take(20)), text(json.drop(20)), done()))
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/polish", polishBody()) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            val frames = parseSse(body)
            assertEquals(listOf("delta", "delta", "done"), frames.map { it.event })
            assertEquals(json, frames.dropLast(1).joinToString("") { it.data["text"]!!.jsonPrimitive.content })
            val d = frames.last().data
            assertEquals("Hello there.", d["polished"]!!.jsonPrimitive.content)
            assertEquals("Hola.", d["translation"]!!.jsonPrimitive.content)
            assertEquals("en", d["source_lang_detected"]!!.jsonPrimitive.content)
            assertEquals(9, d["quota_left"]!!.jsonPrimitive.int)
            assertEquals("polish_translate@1", d["prompt"]!!.jsonPrimitive.content)

            val req = gateway.requests.single()
            assertEquals(2048L, req.maxTokens)
            assertEquals(PromptRegistry().get("polish_translate").system, req.system)
            assertNotNull(req.jsonSchema)
            assertTrue("<script>match_input</script>" in req.user)
            assertTrue("<transcript>um hello there</transcript>" in req.user)
        }
    }

    @Test
    fun `unparseable json at the end is an error event`() {
        val gateway = FakeGateway(listOf(text("""{"polished":"Hel"""), done()))
        testApp(aiServices(gateway)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/polish", polishBody()) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            val frames = parseSse(body)
            assertEquals("error", frames.last().event)
            assertEquals("internal", frames.last().data["error"]!!.jsonPrimitive.content)
            assertTrue(frames.none { it.event == "done" })
        }
    }

    @Test
    fun `script is passed in the user turn`() {
        for ((script, tag) in listOf("ROMAN" to "roman", "NATIVE" to "native", "MATCH_INPUT" to "match_input")) {
            val gateway = FakeGateway(listOf(text(json), done()))
            testApp(aiServices(gateway)) {
                contractCall(HttpMethod.Post, "/v1/polish", polishBody(extra = ""","script":"$script","source_lang":"hi"""")) {
                    authed()
                }
                val user = gateway.requests.single().user
                assertTrue("<script>$tag</script>" in user, user)
                assertTrue("<source_lang>hi</source_lang>" in user)
                assertTrue("<target_lang>es</target_lang>" in user)
            }
        }
    }

    @Test
    fun `unknown script is bad_request`() = testApp(aiServices(FakeGateway(listOf(done())))) {
        val (response, _) = contractCall(
            HttpMethod.Post, "/v1/polish", polishBody(extra = ""","script":"KLINGON""""), requestMustBeValid = false,
        ) { authed() }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(10, quotaLeft())
    }

    @Test
    fun `transcript over 4000 chars is too_long`() = testApp(aiServices(FakeGateway(listOf(done())))) {
        val (response, body) = contractCall(HttpMethod.Post, "/v1/polish", polishBody("a".repeat(4001))) { authed() }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("too_long", Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upstream error before the first delta is 503 and refunds`() {
        testApp(aiServices(FakeGateway(emptyList(), failAt = 0, error = GatewayErrorCode.OVERLOADED))) {
            val (response, _) = contractCall(HttpMethod.Post, "/v1/polish", polishBody()) { authed() }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals(10, quotaLeft())
        }
    }
}
