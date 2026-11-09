package com.manjul.kbapi.routes

import com.manjul.kbapi.billing.PlayState
import com.manjul.kbapi.testing.BillingKeys
import com.manjul.kbapi.testing.FakePlayApi
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.billingServices
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.sub
import com.manjul.kbapi.testing.testApp
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntitlementRoutesTest {
    private val play = FakePlayApi()
    private val services = billingServices(play)

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun verifyBody(token: String = "tok-1", product: String = "pro") =
        """{"purchase_token":"$token","product_id":"$product"}"""

    /** The client's parse (EntitlementClient) and signature check (EntitlementSignature). */
    private fun assertClientAccepts(o: JsonObject) {
        val payload = "v1|${o["pro_active"]!!.jsonPrimitive.boolean}|${o["state"]!!.jsonPrimitive.content}|" +
            "${o["expires_at"]!!.jsonPrimitive.long}|${o["issued_at"]!!.jsonPrimitive.long}"
        assertTrue(BillingKeys.verifies(payload, o["signature"]!!.jsonPrimitive.content), "signature over $payload")
    }

    @Test
    fun `verify an active purchase, then GET returns the same verdict`() = testApp(services) {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        val (r, body) = contractCall(HttpMethod.Post, "/v1/entitlement/verify", verifyBody()) { authed("uid-a") }
        assertEquals(HttpStatusCode.OK, r.status)
        val o = json(body)
        assertEquals(true, o["pro_active"]!!.jsonPrimitive.boolean)
        assertEquals("active", o["state"]!!.jsonPrimitive.content)
        assertEquals(TestKeys.NOW.toEpochMilli(), o["issued_at"]!!.jsonPrimitive.long)
        assertClientAccepts(o)

        val (g, gBody) = contractCall(HttpMethod.Get, "/v1/entitlement") { authed("uid-a") }
        assertEquals(HttpStatusCode.OK, g.status)
        assertEquals(o["expires_at"], json(gBody)["expires_at"])
        assertClientAccepts(json(gBody))
    }

    @Test
    fun `an unknown token or a wrong product is 400 invalid_token`() = testApp(services) {
        val (r, body) = contractCall(HttpMethod.Post, "/v1/entitlement/verify", verifyBody("nope")) { authed() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("invalid_token", json(body)["error"]!!.jsonPrimitive.content)
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        val (w, wBody) = contractCall(HttpMethod.Post, "/v1/entitlement/verify", verifyBody(product = "x")) { authed() }
        assertEquals(HttpStatusCode.BadRequest, w.status)
        assertEquals("invalid_token", json(wBody)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty or oversized token is bad_request`() = testApp(services) {
        val (r, body) = contractCall(
            HttpMethod.Post, "/v1/entitlement/verify", verifyBody(token = ""), requestMustBeValid = false,
        ) { authed() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("bad_request", json(body)["error"]!!.jsonPrimitive.content)
        val (big, _) = contractCall(
            HttpMethod.Post, "/v1/entitlement/verify", verifyBody(token = "a".repeat(5000)), requestMustBeValid = false,
        ) { authed() }
        assertEquals(HttpStatusCode.BadRequest, big.status)
    }

    @Test
    fun `Play down is 503 unavailable, never invalid_token`() = testApp(services) {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        play.down = true
        val (r, body) = contractCall(HttpMethod.Post, "/v1/entitlement/verify", verifyBody()) { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("unavailable", json(body)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `billing not configured is 503 unavailable`() = testApp(services.copy(play = null)) {
        val (r, _) = contractCall(HttpMethod.Post, "/v1/entitlement/verify", verifyBody()) { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        val (g, _) = contractCall(HttpMethod.Get, "/v1/entitlement") { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, g.status)
    }

    @Test
    fun `GET without a purchase is a signed none`() = testApp(services) {
        val (r, body) = contractCall(HttpMethod.Get, "/v1/entitlement") { authed("fresh") }
        assertEquals(HttpStatusCode.OK, r.status)
        val o = json(body)
        assertEquals(false, o["pro_active"]!!.jsonPrimitive.boolean)
        assertEquals("none", o["state"]!!.jsonPrimitive.content)
        assertClientAccepts(o)
    }

    @Test
    fun `no credentials is 401`() = testApp(services) {
        val (r, _) = contractCall(HttpMethod.Get, "/v1/entitlement", requestMustBeValid = false)
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val (v, _) = contractCall(HttpMethod.Post, "/v1/entitlement/verify", verifyBody(), requestMustBeValid = false) {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
        }
        assertEquals(HttpStatusCode.Unauthorized, v.status)
    }

    @Test
    fun `verify plus a real App Check pass makes the quota tier PRO`() = testApp(services) {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        contractCall(HttpMethod.Post, "/v1/entitlement/verify", verifyBody()) { authed("uid-a") }
        val (_, body) = contractCall(HttpMethod.Get, "/v1/quota") { authed("uid-a") }
        assertEquals("PRO", json(body)["tier"]!!.jsonPrimitive.content)
        assertEquals("150", json(body)["ai_left_today"]!!.jsonPrimitive.content)
    }
}
