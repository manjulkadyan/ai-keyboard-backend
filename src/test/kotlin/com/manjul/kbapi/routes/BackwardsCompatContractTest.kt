package com.manjul.kbapi.routes

import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.manjul.kbapi.testing.Contract
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.billingServices
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.testApp
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ARCH-006: an S3 client keeps working against the S4 spec and server. S5 adds `/v1/dictionary`; the S3 and S4
 * apps never call it, so these tests only prove that the older routes and error codes do not change.
 */
class BackwardsCompatContractTest {

    private fun request(method: Request.Method, path: String, body: String?) =
        SimpleRequest.Builder(method, path)
            .withHeader("X-Firebase-AppCheck", "t")
            .withHeader("Authorization", "Bearer t")
            .apply { if (body != null) withContentType("application/json").withBody(body) }
            .build()

    private fun response(status: Int, body: String, contentType: String = "application/json") =
        SimpleResponse.Builder(status)
            .withHeader("X-Request-Id", "r")
            .withContentType(contentType)
            .withBody(body)
            .build()

    /** Sprint 3 request and response fixtures, as the S3 client sends and expects them. */
    private val s3Fixtures = listOf(
        Triple(request(Request.Method.GET, "/v1/quota", null),
            response(200, """{"ai_left_today":10,"resets_at":"2026-10-27T00:00:00Z","tier":"FREE"}"""), "quota"),
        Triple(request(Request.Method.POST, "/v1/rewrite", """{"mode":"fix","locale":"en","text":"hi","client_req_id":"r1"}"""),
            response(200, "event: done\ndata: {}\n\n", "text/event-stream"), "rewrite"),
        Triple(request(Request.Method.POST, "/v1/rewrite", """{"mode":"fix","modes":["formal","friendly"],"locale":"en","text":"hi","client_req_id":"r1"}"""),
            response(200, """{"variants":[{"mode":"formal","text":"x"}],"quota_left":1,"prompt":"multi_tone@1"}"""), "multi"),
        Triple(request(Request.Method.POST, "/v1/polish", """{"transcript":"hi","target_lang":"en","client_req_id":"r1"}"""),
            response(200, "event: done\ndata: {}\n\n", "text/event-stream"), "polish"),
        Triple(request(Request.Method.POST, "/v1/ai/report", """{"client_req_id":"r1","prompt_id":"rewrite@1","reason":"wrong","include_text":false}"""),
            response(200, """{"ok":true}"""), "report"),
        Triple(request(Request.Method.GET, "/v1/quota", null), response(401, """{"error":"app_check"}"""), "401"),
        Triple(request(Request.Method.POST, "/v1/rewrite", """{"mode":"fix","locale":"en","text":"hi","client_req_id":"r1"}"""),
            response(402, """{"error":"quota","resets_at":"2026-10-27T00:00:00Z"}"""), "402"),
    )

    @Test
    fun `every S3 fixture still validates against the new spec`() {
        for ((req, resp, name) in s3Fixtures) {
            val report = Contract.validator.validate(req, resp)
            if (report.hasErrors()) fail("S3 fixture $name no longer validates:\n$report")
        }
    }

    @Test
    fun `a client without billing still gets tier FREE from quota`() = testApp(billingServices()) {
        val (r, body) = contractCall(HttpMethod.Get, "/v1/quota") { authed("s3-client") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("FREE", Json.parseToJsonElement(body).jsonObject["tier"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the new entitlement fields are not required, and an S3-shaped entitlement validates`() {
        val spec = javaClass.getResource("/openapi.yaml")!!.readText()
        val entitlement = spec.substringAfter("\n    Entitlement:\n").lineSequence()
            .takeWhile { it.isEmpty() || it.startsWith("      ") }
            .joinToString("\n")
        val required = Regex("required: \\[([^]]*)]").find(entitlement)?.groupValues?.get(1).orEmpty()
        assertTrue("state" !in required && "issued_at" !in required, "new fields must stay optional: $required")
        assertTrue(listOf("pro_active", "expires_at", "signature").all { it in required })

        val s3Shaped = response(200, """{"pro_active":false,"expires_at":0,"signature":"x"}""")
        val report = Contract.validator.validate(request(Request.Method.GET, "/v1/entitlement", null), s3Shaped)
        if (report.hasErrors()) fail("an entitlement without state and issued_at must validate:\n$report")
    }

    /** Sprint 4 request and response fixtures, as the S4 client sends and expects them. */
    private val s4Fixtures = listOf(
        Triple(request(Request.Method.POST, "/v1/entitlement/verify", """{"purchase_token":"t","product_id":"pro"}"""),
            response(200, """{"pro_active":true,"expires_at":1,"signature":"s","state":"active","issued_at":1}"""), "verify"),
        Triple(request(Request.Method.POST, "/v1/entitlement/verify", """{"purchase_token":"t","product_id":"pro"}"""),
            response(400, """{"error":"invalid_token"}"""), "verify 400"),
        Triple(request(Request.Method.GET, "/v1/entitlement", null),
            response(503, """{"error":"unavailable"}"""), "entitlement 503"),
        Triple(request(Request.Method.POST, "/v1/account/delete", """{"client_req_id":"d1"}"""),
            response(200, """{"deleted":true}"""), "delete"),
    )

    @Test
    fun `every S4 fixture still validates against the S5 spec`() {
        for ((req, resp, name) in s4Fixtures) {
            val report = Contract.validator.validate(req, resp)
            if (report.hasErrors()) fail("S4 fixture $name no longer validates:\n$report")
        }
    }

    @Test
    fun `no S3 or S4 error code was removed`() {
        val old = listOf(
            "too_long", "bad_request", "app_check", "auth", "quota", "rate_limited", "refused", "ai_paused",
            "overloaded", "internal", "not_found", "method_not_allowed", "invalid_token", "unavailable",
        )
        for (code in old) {
            val r = Contract.validator.validate(request(Request.Method.GET, "/v1/quota", null), response(401, """{"error":"$code"}"""))
            if (r.hasErrors()) fail("error code $code no longer validates:\n$r")
        }
    }
}
