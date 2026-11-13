package com.manjul.kbapi.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.manjul.kbapi.ACCESS_LOGGER
import com.manjul.kbapi.LogField
import com.manjul.kbapi.auth.Principal
import com.manjul.kbapi.billing.PlayState
import com.manjul.kbapi.billing.RtdnRoutesTest
import com.manjul.kbapi.billing.RtdnType
import com.manjul.kbapi.dictionary.DictionaryEntry
import com.manjul.kbapi.dictionary.FakeDictionarySource
import com.manjul.kbapi.testing.FakePlayApi
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.billingServices
import com.manjul.kbapi.testing.sub
import com.manjul.kbapi.testing.testApp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OPS-001: no purchase token, order id, bearer value, JWT or raw uid reaches a log line on any new route.
 * S5: no dictionary query reaches a log line either (PC-10).
 */
class CredentialLogTest {
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val play = FakePlayApi()
    private val services = billingServices(play)

    private val uid = "raw-uid-CRED-7f3a"
    private val token = "PURCHASE-TOKEN-SECRET-aa.bb-cc"
    private val order = "GPA.3399-1111-2222-33333"

    @BeforeEach
    fun attach() {
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        root.detachAppender(appender)
        appender.stop()
    }

    private fun authedHeaders(b: io.ktor.client.request.HttpRequestBuilder, idToken: String, appCheck: String) {
        b.header("X-Firebase-AppCheck", appCheck)
        b.header("Authorization", "Bearer $idToken")
    }

    private fun assertClean(vararg secrets: String) {
        assertTrue(appender.list.isNotEmpty())
        for (event in appender.list) {
            val line = event.formattedMessage
            for (s in secrets.toList() + listOf(token, order, uid, "GPA.", "Bearer", "eyJ")) {
                assertFalse(line.contains(s), "log line leaked a credential ($s): $line")
            }
            assertNull(event.throwableProxy, "exception logged from ${event.loggerName}")
            event.mdcPropertyMap.values.forEach { v -> assertFalse(v.contains(token) || v.contains(uid)) }
            event.argumentArray?.forEach { a -> assertFalse(a.toString().contains(token) || a.toString().contains(uid)) }
        }
        for (event in appender.list.filter { it.loggerName == ACCESS_LOGGER }) {
            val fields = Json.parseToJsonElement(event.formattedMessage).jsonObject
            assertTrue(fields.keys.all { k -> LogField.entries.any { it.key == k } }, "non allow-listed field")
            val hash = fields["uid_hash"]!!.jsonPrimitive.content
            assertTrue(hash == "-" || hash == Principal.of(uid).uidHash, "unexpected uid_hash $hash")
        }
    }

    @Test
    fun `entitlement routes log only the uid hash, on success and on every error path`() = testApp(services) {
        val idToken = TestKeys.idToken(uid)
        val appCheck = TestKeys.appCheckToken()
        val body = """{"purchase_token":"$token","product_id":"pro"}"""
        suspend fun verify() = client.post("/v1/entitlement/verify") {
            authedHeaders(this, idToken, appCheck)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        verify() // 400 invalid_token
        play.subs[token] = sub(PlayState.ACTIVE)
        verify() // 200
        play.failNext = 1
        verify() // retried and recovered: WARN
        play.down = true
        verify() // 503: ERROR
        play.down = false
        client.get("/v1/entitlement") { authedHeaders(this, idToken, appCheck) }
        client.post("/v1/entitlement/verify") { // 401 with the token in the body
            header("Authorization", "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        client.post("/v1/entitlement/verify") { // 400 bad body
            authedHeaders(this, idToken, appCheck)
            contentType(ContentType.Application.Json)
            setBody("""{"purchase_token":"$token","product_id":""")
        }
        client.post("/v1/account/delete") {
            authedHeaders(this, idToken, appCheck)
            contentType(ContentType.Application.Json)
            setBody("""{"client_req_id":"d1"}""")
        }
        assertClean(idToken, appCheck)
        assertTrue(appender.list.any { it.level.toString() == "WARN" && it.loggerName.startsWith("kbapi.billing") })
        assertTrue(appender.list.any { it.level.toString() == "ERROR" && it.loggerName.startsWith("kbapi.billing") })
        val routes = appender.list.filter { it.loggerName == ACCESS_LOGGER }
            .map { Json.parseToJsonElement(it.formattedMessage).jsonObject["route"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("/v1/entitlement/verify", "/v1/entitlement", "/v1/account/delete"), routes)
    }

    @Test
    fun `RTDN logs the type number and never the token, order id or JWT`() = testApp(services) {
        play.subs[token] = sub(PlayState.ACTIVE, acknowledged = true)
        services.entitlementService().verify(uid, token, "pro")
        val jwt = TestKeys.pubSubToken()
        suspend fun deliver(data: String, bearer: String = jwt, id: String) = client.post("/internal/rtdn") {
            header("Authorization", "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody(RtdnRoutesTest.push(data, id))
        }
        deliver(RtdnRoutesTest.subscriptionData(token, RtdnType.RENEWED), id = "m1")
        deliver(RtdnRoutesTest.subscriptionData(token, RtdnType.RENEWED), id = "m1")
        val voided = """{"version":"1.0","packageName":"com.manjul.aikeyboard","eventTimeMillis":"1",""" +
            """"voidedPurchaseNotification":{"purchaseToken":"$token","orderId":"$order","productType":1}}"""
        deliver(voided, id = "m2")
        deliver(RtdnRoutesTest.subscriptionData(token, RtdnType.CANCELED), bearer = TestKeys.pubSubToken(audience = "x"), id = "m3")
        assertClean(jwt)
        val lines = appender.list.filter { it.loggerName == ACCESS_LOGGER }
            .map { Json.parseToJsonElement(it.formattedMessage).jsonObject }
            .filter { it["route"]!!.jsonPrimitive.content == "/internal/rtdn" }
        assertEquals(4, lines.size)
        assertEquals("2", lines.first()["rtdn_type"]!!.jsonPrimitive.content)
        assertTrue(lines.all { it["uid_hash"]!!.jsonPrimitive.content == "-" })
    }

    @Test
    fun `a dictionary lookup leaves the word in no log line, on success and on every error path`() {
        val marker = "zyxwordleak"
        val dict = FakeDictionarySource(mapOf(("en" to marker) to listOf(DictionaryEntry(marker, "noun", "d", null, emptyList()))))
        testApp(services.copy(dictionary = dict)) {
            val idToken = TestKeys.idToken(uid)
            val appCheck = TestKeys.appCheckToken()
            suspend fun lookup(body: String, authed: Boolean = true) = client.post("/v1/dictionary") {
                if (authed) authedHeaders(this, idToken, appCheck)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            lookup("""{"word":"$marker","lang":"en"}""") // 200
            lookup("""{"word":"$marker","lang":"en"}""") // 200 from the cache
            lookup("""{"word":"${marker}x","lang":"en"}""") // 404
            lookup("""{"word":"$marker $marker","lang":"en"}""") // 400 two words
            lookup("""{"word":"$marker","lang":"$marker"}""") // 400 lang
            lookup("""{"word":"$marker","lang":"en"""") // 400 malformed
            lookup("""{"word":"$marker","lang":"en"}""", authed = false) // 401
            dict.down = true
            lookup("""{"word":"${marker}y","lang":"en"}""") // 503: WARN
            dict.down = false
            repeat(60) { lookup("""{"word":"$marker","lang":"en"}""") } // ends in 429
            assertClean(idToken, appCheck, marker)
            assertTrue(appender.list.any { it.level.toString() == "WARN" && it.loggerName == "kbapi.dictionary" })
            val statuses = appender.list.filter { it.loggerName == ACCESS_LOGGER }
                .map { Json.parseToJsonElement(it.formattedMessage).jsonObject }
                .onEach { assertEquals("/v1/dictionary", it["route"]!!.jsonPrimitive.content) }
                .map { it["status"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("200", "404", "400", "401", "503", "429"), statuses)
        }
    }
}
