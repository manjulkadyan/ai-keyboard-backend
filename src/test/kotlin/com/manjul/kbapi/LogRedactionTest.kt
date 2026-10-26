package com.manjul.kbapi

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.manjul.kbapi.auth.Principal
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.testApp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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

class LogRedactionTest {

    private val secret = "SECRET-PAYLOAD-123"
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

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

    private fun accessLines() = appender.list.filter { it.loggerName == ACCESS_LOGGER }

    private fun assertNoSecretAnywhere() {
        for (event in appender.list) {
            assertFalse(event.formattedMessage.contains(secret), "log line leaked input: ${event.formattedMessage}")
            assertFalse(event.message.orEmpty().contains(secret))
            assertNull(event.throwableProxy, "exception logged with a stack/message from ${event.loggerName}")
            event.keyValuePairs?.forEach { assertFalse(it.value.toString().contains(secret)) }
            event.mdcPropertyMap.values.forEach { assertFalse(it.contains(secret)) }
        }
    }

    private fun assertAccessFields(expectedStatus: Int, expectedUidHash: String?) {
        val line = accessLines().last()
        val fields = Json.parseToJsonElement(line.formattedMessage).jsonObject
        for (key in listOf("uid_hash", "route", "status", "latency_ms", "request_id")) {
            assertTrue(key in fields, "access line misses $key: ${line.formattedMessage}")
        }
        assertEquals(expectedStatus.toString(), fields["status"]!!.jsonPrimitive.content)
        if (expectedUidHash != null) assertEquals(expectedUidHash, fields["uid_hash"]!!.jsonPrimitive.content)
        assertTrue(fields.keys.all { key -> LogField.entries.any { it.key == key } }, "non allow-listed field")
    }

    @Test
    fun `400 path logs no request text`() = testApp(extraRoutes = errorTestRoutes) {
        val response = client.post("/v1/test/json") {
            authed("log-uid")
            contentType(ContentType.Application.Json)
            setBody("""{"text": "$secret", "count": "$secret"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNoSecretAnywhere()
        assertAccessFields(400, Principal.of("log-uid").uidHash)
    }

    @Test
    fun `401 path logs no request text or token`() = testApp(extraRoutes = errorTestRoutes) {
        val response = client.post("/v1/test/json") {
            header("X-Firebase-AppCheck", secret)
            header("Authorization", "Bearer $secret")
            contentType(ContentType.Application.Json)
            setBody("""{"text": "$secret", "count": 1}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNoSecretAnywhere()
        assertAccessFields(401, expectedUidHash = "-")
    }

    @Test
    fun `500 path logs the exception class only`() = testApp(extraRoutes = errorTestRoutes) {
        val response = client.get("/v1/test/boom?q=$secret") {
            authed("log-uid")
            header("X-Extra", secret)
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertNoSecretAnywhere()
        assertAccessFields(500, Principal.of("log-uid").uidHash)
        val fields = Json.parseToJsonElement(accessLines().last().formattedMessage).jsonObject
        assertEquals("java.lang.IllegalStateException", fields["error_class"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown paths are not logged verbatim`() = testApp {
        client.get("/$secret")
        client.get("/v1/$secret") { authed() }
        assertNoSecretAnywhere()
        assertTrue(accessLines().isNotEmpty())
    }

    @Test
    fun `uid is never logged in clear`() = testApp {
        client.get("/v1/quota") { authed("plain-uid-value") }
        assertTrue(appender.list.none { it.formattedMessage.contains("plain-uid-value") })
        assertTrue(appender.list.none { it.formattedMessage.contains(TestKeys.idToken("plain-uid-value").take(40)) })
        assertAccessFields(200, Principal.of("plain-uid-value").uidHash)
    }
}
