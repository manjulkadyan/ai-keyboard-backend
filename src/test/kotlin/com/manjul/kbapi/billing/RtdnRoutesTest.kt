package com.manjul.kbapi.billing

import com.manjul.kbapi.Services
import com.manjul.kbapi.testing.FakePlayApi
import com.manjul.kbapi.testing.PRO
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.billingServices
import com.manjul.kbapi.testing.sub
import com.manjul.kbapi.testing.testApp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtdnRoutesTest {
    private val play = FakePlayApi()
    private val services: Services = billingServices(play)
    private val store get() = services.limits.entitlements

    private fun link(uid: String, token: String, state: PlayState = PlayState.ACTIVE) = runBlocking {
        play.subs[token] = sub(state, acknowledged = true)
        services.entitlementService().verify(uid, token, PRO)
    }

    companion object {
        fun subscriptionData(token: String, type: Int, pkg: String = "com.manjul.aikeyboard") =
            """{"version":"1.0","packageName":"$pkg","eventTimeMillis":"1761472800000",""" +
                """"subscriptionNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"$token",""" +
                """"subscriptionId":"pro"}}"""

        fun push(data: String, messageId: String = "m-1"): String {
            val b64 = Base64.getEncoder().encodeToString(data.toByteArray())
            return """{"message":{"data":"$b64","messageId":"$messageId","publishTime":"2026-10-26T10:00:00Z"},""" +
                """"subscription":"projects/kb-test-project/subscriptions/rtdn-push"}"""
        }
    }

    private suspend fun ApplicationTestBuilder.deliver(
        body: String,
        token: String = TestKeys.pubSubToken(),
    ): HttpResponse = client.post("/internal/rtdn") {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `a valid JWT and a type 4 message give 204 and a Play re-read`() = testApp(services) {
        link("uid-a", "tok-1")
        val before = play.reads
        assertEquals(HttpStatusCode.NoContent, deliver(push(subscriptionData("tok-1", RtdnType.PURCHASED))).status)
        assertEquals(before + 1, play.reads)
        assertTrue(store.byToken("tok-1")!!.proActive)
    }

    @Test
    fun `the same message twice ends in the same record and reads Play once`() = testApp(services) {
        link("uid-a", "tok-1")
        play.subs["tok-1"] = sub(PlayState.ON_HOLD, acknowledged = true)
        val before = play.reads
        repeat(2) {
            assertEquals(HttpStatusCode.NoContent, deliver(push(subscriptionData("tok-1", RtdnType.ON_HOLD))).status)
        }
        assertEquals(before + 1, play.reads, "a redelivered messageId is not processed again")
        assertEquals(SubState.ON_HOLD, store.byToken("tok-1")!!.state)
    }

    @Test
    fun `type 12 gives revoked and no pro`() = testApp(services) {
        link("uid-a", "tok-1")
        play.subs["tok-1"] = sub(PlayState.EXPIRED, expiryMs = TestKeys.NOW.toEpochMilli() - 1, acknowledged = true)
        assertEquals(HttpStatusCode.NoContent, deliver(push(subscriptionData("tok-1", RtdnType.REVOKED))).status)
        val r = store.byToken("tok-1")!!
        assertEquals(SubState.REVOKED, r.state)
        assertFalse(r.proActive)
    }

    @Test
    fun `a voided purchase gives revoked`() = testApp(services) {
        link("uid-a", "tok-1")
        val data = """{"version":"1.0","packageName":"com.manjul.aikeyboard","eventTimeMillis":"1",""" +
            """"voidedPurchaseNotification":{"purchaseToken":"tok-1","orderId":"GPA.0000-0000-0000-00000",""" +
            """"productType":1,"refundType":1}}"""
        assertEquals(HttpStatusCode.NoContent, deliver(push(data)).status)
        assertEquals(SubState.REVOKED, store.byToken("tok-1")!!.state)
    }

    @Test
    fun `a stale type 3 after a newer Play state keeps the Play state`() = testApp(services) {
        link("uid-a", "tok-1")
        // The user canceled, then restored: Play is ACTIVE again when the old CANCELED message lands.
        assertEquals(HttpStatusCode.NoContent, deliver(push(subscriptionData("tok-1", RtdnType.CANCELED))).status)
        val r = store.byToken("tok-1")!!
        assertEquals(SubState.ACTIVE, r.state)
        assertTrue(r.proActive)
    }

    @Test
    fun `an unknown token gives 204 and no record`() = testApp(services) {
        play.subs["tok-x"] = sub(PlayState.ACTIVE)
        assertEquals(HttpStatusCode.NoContent, deliver(push(subscriptionData("tok-x", RtdnType.PURCHASED))).status)
        assertNull(store.byToken("tok-x"))
    }

    @Test
    fun `a linkedPurchaseToken removes the old record and keeps the uid`() = testApp(services) {
        link("uid-a", "tok-1")
        play.subs["tok-2"] = sub(PlayState.ACTIVE, linked = "tok-1", acknowledged = true)
        assertEquals(HttpStatusCode.NoContent, deliver(push(subscriptionData("tok-2", RtdnType.PURCHASED))).status)
        assertNull(store.byToken("tok-1"))
        assertEquals("uid-a", store.byToken("tok-2")?.uid)
    }

    @Test
    fun `a testNotification or another package gives 204`() = testApp(services) {
        val test = """{"version":"1.0","packageName":"com.manjul.aikeyboard","eventTimeMillis":"1",""" +
            """"testNotification":{"version":"1.0"}}"""
        assertEquals(HttpStatusCode.NoContent, deliver(push(test)).status)
        link("uid-a", "tok-1")
        val before = play.reads
        val other = subscriptionData("tok-1", RtdnType.CANCELED, pkg = "com.example.other")
        assertEquals(HttpStatusCode.NoContent, deliver(push(other, messageId = "m-2")).status)
        assertEquals(before, play.reads)
    }

    @Test
    fun `undecodable data is acknowledged and dropped`() = testApp(services) {
        assertEquals(HttpStatusCode.NoContent, deliver(push("not json")).status)
        assertEquals(
            HttpStatusCode.NoContent,
            deliver("""{"message":{"data":"%%%","messageId":"m-3"}}""").status,
        )
    }

    @Test
    fun `a bad audience, a missing token or another key give 401 and no work`() = testApp(services) {
        link("uid-a", "tok-1")
        val before = play.reads
        val body = push(subscriptionData("tok-1", RtdnType.CANCELED))
        assertEquals(HttpStatusCode.Unauthorized, deliver(body, TestKeys.pubSubToken(audience = "https://x")).status)
        assertEquals(HttpStatusCode.Unauthorized, deliver(body, TestKeys.pubSubToken(key = TestKeys.otherKey)).status)
        val noAuth = client.post("/internal/rtdn") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        assertEquals(before, play.reads)
    }

    @Test
    fun `Play down gives 503 so Pub Sub retries, and the retry is processed`() = testApp(services) {
        link("uid-a", "tok-1")
        play.subs["tok-1"] = sub(PlayState.ON_HOLD, acknowledged = true)
        play.down = true
        val body = push(subscriptionData("tok-1", RtdnType.ON_HOLD), messageId = "m-9")
        assertEquals(HttpStatusCode.ServiceUnavailable, deliver(body).status)
        play.down = false
        assertEquals(HttpStatusCode.NoContent, deliver(body).status)
        assertEquals(SubState.ON_HOLD, store.byToken("tok-1")!!.state)
    }

    @Test
    fun `RTDN is refused when push auth is not configured`() = testApp(services.copy(pushAuth = null)) {
        assertEquals(
            HttpStatusCode.Unauthorized,
            deliver(push(subscriptionData("tok-1", RtdnType.CANCELED))).status,
        )
    }
}
