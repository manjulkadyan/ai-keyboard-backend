package com.manjul.kbapi.routes

import com.manjul.kbapi.limits.Limits
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.testApp
import com.manjul.kbapi.testing.testConfig
import com.manjul.kbapi.testing.testServices
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.auth.Principal
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaRoutesTest {
    @Test
    fun `quota returns the full free allowance and matches the contract`() = testApp {
        val (response, body) = contractCall(HttpMethod.Get, "/v1/quota") { authed() }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(10, json["ai_left_today"]!!.jsonPrimitive.int)
        assertEquals("FREE", json["tier"]!!.jsonPrimitive.content)
        assertEquals("2026-10-27T00:00:00Z", json["resets_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quota reflects reservations`() {
        val limits = Limits.inMemory(testConfig(), TestKeys.clock)
        runBlocking { limits.quota.reserve(Principal.of("test-uid-1"), "r1") }
        testApp(services = testServices().copy(limits = limits)) {
            val (_, body) = contractCall(HttpMethod.Get, "/v1/quota") { authed("test-uid-1") }
            assertEquals(9, Json.parseToJsonElement(body).jsonObject["ai_left_today"]!!.jsonPrimitive.int)
        }
    }
}
