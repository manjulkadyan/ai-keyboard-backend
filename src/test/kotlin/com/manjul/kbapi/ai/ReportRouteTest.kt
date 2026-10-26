package com.manjul.kbapi.ai

import com.manjul.kbapi.report.InMemoryReportStore
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.quotaLeft
import com.manjul.kbapi.testing.testApp
import com.manjul.kbapi.testing.testServices
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportRouteTest {
    private fun body(includeText: Boolean, text: String? = null, reason: String = "wrong") =
        """{"client_req_id":"r1","prompt_id":"rewrite@1","reason":"$reason","include_text":$includeText""" +
            (text?.let { ""","text":"$it"""" } ?: "") + "}"

    @Test
    fun `text is dropped unless include_text and the report expires in 90 days`() {
        val store = InMemoryReportStore()
        testApp(testServices().copy(reports = store)) {
            val (response, body) = contractCall(HttpMethod.Post, "/v1/ai/report", body(false, "private words")) { authed() }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"ok":true}""", body)
            val stored = store.all().single()
            assertNull(stored.text)
            assertEquals("rewrite@1", stored.promptId)
            assertEquals("wrong", stored.reason)
            assertEquals(TestKeys.NOW, stored.createdAt)
            assertEquals(TestKeys.NOW.plus(Duration.ofDays(90)), stored.expiresAt)
            assertEquals(10, quotaLeft())
        }
    }

    @Test
    fun `text is kept when ticked and capped at 4000 chars`() {
        val store = InMemoryReportStore()
        testApp(testServices().copy(reports = store)) {
            contractCall(HttpMethod.Post, "/v1/ai/report", body(true, "a".repeat(5000), reason = "offensive")) { authed() }
            assertEquals(4000, store.all().single().text!!.length)
        }
    }

    @Test
    fun `unknown reason is bad_request`() {
        val store = InMemoryReportStore()
        testApp(testServices().copy(reports = store)) {
            val (response, _) = contractCall(
                HttpMethod.Post, "/v1/ai/report", body(false, reason = "harmful"), requestMustBeValid = false,
            ) { authed() }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, store.all().size)
        }
    }
}
