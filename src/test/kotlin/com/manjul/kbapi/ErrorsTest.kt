package com.manjul.kbapi

import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.testApp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
data class EchoBody(val text: String, val count: Int)

/** Test-only routes, outside the OpenAPI contract. */
val errorTestRoutes: Route.() -> Unit = {
    post("/v1/test/json") {
        val body = call.receive<EchoBody>()
        call.respond(mapOf("len" to body.text.length))
    }
    get("/v1/test/boom") { throw IllegalStateException("boom SECRET-PAYLOAD-123") }
    get("/v1/test/quota") { throw ApiException(Errors.quota(Instant.parse("2026-10-27T00:00:00Z"))) }
    get("/v1/test/rate") { throw ApiException(Errors.rateLimited(7)) }
    get("/v1/test/paused") { throw ApiException(Errors.aiPaused()) }
    get("/v1/test/refused") { throw ApiException(Errors.refused()) }
    get("/v1/test/too-long") { throw ApiException(Errors.tooLong()) }
}

class ErrorsTest {

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun HttpResponse.requestId() = headers["X-Request-Id"]

    @Test
    fun `malformed json is bad_request without echoing input`() = testApp(extraRoutes = errorTestRoutes) {
        val inputs = listOf(
            """{"text": "SECRET-PAYLOAD-123", "count": """,
            """{"text": "SECRET-PAYLOAD-123", "count": "SECRET-NUM"}""",
            """SECRET-PAYLOAD-123""",
            """{"count": 1, "extra_SECRET-PAYLOAD-123": true}""",
        )
        for (input in inputs) {
            val response = client.post("/v1/test/json") {
                authed()
                contentType(ContentType.Application.Json)
                setBody(input)
            }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, input)
            assertEquals("bad_request", json(body)["error"]?.jsonPrimitive?.content)
            assertFalse(body.contains("SECRET"), "error body echoed input: $body")
            assertFalse(body.contains("count"), "error body leaked parser detail: $body")
            assertNotNull(response.requestId())
        }
    }

    @Test
    fun `every response carries X-Request-Id`() = testApp(extraRoutes = errorTestRoutes) {
        val responses = listOf(
            client.get("/healthz"),
            client.get("/v1/quota") { authed() },
            client.get("/v1/quota"),
            client.get("/nope"),
            client.get("/v1/test/boom") { authed() },
            client.get("/v1/test/quota") { authed() },
        )
        val ids = responses.map { r -> assertNotNull(r.requestId(), "missing on ${r.call.request.url}") }
        assertEquals(ids.size, ids.toSet().size, "request ids must be unique")
    }

    @Test
    fun `unhandled exception is 500 internal with no message`() = testApp(extraRoutes = errorTestRoutes) {
        val response = client.get("/v1/test/boom") { authed() }
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("internal", json(body)["error"]?.jsonPrimitive?.content)
        assertFalse(body.contains("SECRET"))
        assertFalse(body.contains("IllegalState"))
    }

    @Test
    fun `error codes map to status and extras`() = testApp(extraRoutes = errorTestRoutes) {
        val quota = client.get("/v1/test/quota") { authed() }
        assertEquals(HttpStatusCode.PaymentRequired, quota.status)
        val quotaBody = json(quota.bodyAsText())
        assertEquals("quota", quotaBody["error"]?.jsonPrimitive?.content)
        assertEquals("2026-10-27T00:00:00Z", quotaBody["resets_at"]?.jsonPrimitive?.content)

        val rate = client.get("/v1/test/rate") { authed() }
        assertEquals(HttpStatusCode.TooManyRequests, rate.status)
        assertEquals("7", rate.headers["Retry-After"])
        assertEquals("rate_limited", json(rate.bodyAsText())["error"]?.jsonPrimitive?.content)

        val paused = client.get("/v1/test/paused") { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, paused.status)
        assertEquals("ai_paused", json(paused.bodyAsText())["error"]?.jsonPrimitive?.content)

        val refused = client.get("/v1/test/refused") { authed() }
        assertEquals(451, refused.status.value)
        assertEquals("refused", json(refused.bodyAsText())["error"]?.jsonPrimitive?.content)

        val tooLong = client.get("/v1/test/too-long") { authed() }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals("too_long", json(tooLong.bodyAsText())["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown route is a json 404`() = testApp {
        val response = client.get("/nope")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not_found"))
    }
}
