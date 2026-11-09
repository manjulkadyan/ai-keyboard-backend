package com.manjul.kbapi.routes

import com.manjul.kbapi.AuthMode
import com.manjul.kbapi.auth.DevAuth
import com.manjul.kbapi.billing.PlayState
import com.manjul.kbapi.testing.FakePlayApi
import com.manjul.kbapi.testing.FakeUserDeleter
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.billingServices
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.sub
import com.manjul.kbapi.testing.testApp
import com.manjul.kbapi.testing.testConfig
import com.manjul.kbapi.testing.testServices
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountRoutesTest {
    private val deleter = FakeUserDeleter()
    private val play = FakePlayApi()
    private val services = billingServices(play, deleter)
    private val deleteBody = """{"client_req_id":"del-1"}"""

    @Test
    fun `no ID token is 401 and deletes nothing`() = testApp(services) {
        val (r, _) = contractCall(HttpMethod.Post, "/v1/account/delete", deleteBody, requestMustBeValid = false) {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val (n, _) = contractCall(HttpMethod.Post, "/v1/account/delete", deleteBody, requestMustBeValid = false)
        assertEquals(HttpStatusCode.Unauthorized, n.status)
        assertTrue(deleter.deleted.isEmpty())
    }

    @Test
    fun `delete removes the caller's data, deletes the Firebase user and is idempotent`() = testApp(services) {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        contractCall(HttpMethod.Post, "/v1/entitlement/verify", """{"purchase_token":"tok-1","product_id":"pro"}""") {
            authed("uid-a")
        }
        repeat(2) {
            val (r, text) = contractCall(HttpMethod.Post, "/v1/account/delete", deleteBody) { authed("uid-a") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("true", Json.parseToJsonElement(text).jsonObject["deleted"]!!.jsonPrimitive.content)
        }
        assertNull(services.limits.entitlements.byUid("uid-a"))
        assertEquals(listOf("uid-a", "uid-a"), deleter.deleted)
    }

    @Test
    fun `the target is the verified uid, never the body`() = testApp(services) {
        val sneaky = """{"client_req_id":"del-1","uid":"victim"}"""
        val (r, _) = contractCall(HttpMethod.Post, "/v1/account/delete", sneaky, requestMustBeValid = false) {
            authed("uid-a")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(listOf("uid-a"), deleter.deleted)
    }

    @Test
    fun `a missing or oversized client_req_id is bad_request`() = testApp(services) {
        val (r, _) = contractCall(HttpMethod.Post, "/v1/account/delete", "{}", requestMustBeValid = false) { authed() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val long = """{"client_req_id":"${"x".repeat(65)}"}"""
        val (l, _) = contractCall(HttpMethod.Post, "/v1/account/delete", long, requestMustBeValid = false) { authed() }
        assertEquals(HttpStatusCode.BadRequest, l.status)
        assertTrue(deleter.deleted.isEmpty())
    }

    @Test
    fun `dev auth skips the Firebase call`() {
        val dev = billingServices(play, deleter, base = testServices(testConfig(AuthMode.DEV)))
        testApp(dev) {
            val response = client.post("/v1/account/delete") {
                header(DevAuth.HEADER, "dev-uid")
                contentType(ContentType.Application.Json)
                setBody(deleteBody)
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(deleter.deleted.isEmpty())
        }
    }

    @Test
    fun `the deletion page is public HTML with the in-app steps and the Play cancel link`() = testApp(services) {
        val r = client.get("/delete-account")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Text.Html.withoutParameters(), r.contentType()?.withoutParameters())
        val html = r.bodyAsText()
        assertTrue(html.contains("Delete my data"))
        assertTrue(html.contains("play.google.com/store/account/subscriptions"))
        assertTrue(html.contains("mailto:"))
        assertFalse(html.contains("<form"), "decision 21: no sign-in or form in MVP")
        assertTrue(r.headers["Content-Security-Policy"].orEmpty().contains("default-src 'none'"))
    }
}

