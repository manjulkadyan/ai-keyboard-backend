package com.manjul.kbapi.routes

import com.manjul.kbapi.dictionary.DictionaryEntry
import com.manjul.kbapi.dictionary.FakeDictionarySource
import com.manjul.kbapi.testing.MutableClock
import com.manjul.kbapi.testing.TestKeys
import com.manjul.kbapi.testing.authed
import com.manjul.kbapi.testing.billingServices
import com.manjul.kbapi.testing.contractCall
import com.manjul.kbapi.testing.testServices
import com.manjul.kbapi.testing.testApp
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictionaryRoutesTest {
    private val run = DictionaryEntry("run", "verb", "To move fast on foot.", "Run home.", listOf("sprint"))
    private val runNoun = DictionaryEntry("run", "noun", "An act of running.", null, emptyList())
    private val source = FakeDictionarySource(mapOf(("en" to "run") to listOf(run, runNoun)))
    private val clock = MutableClock()
    private val services = testServices().copy(clock = clock, dictionary = source)

    private fun body(word: String, lang: String = "en") = """{"word":"$word","lang":"$lang"}"""
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a known word is 200 with the normalised shape`() = testApp(services) {
        val (r, text) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed() }
        assertEquals(HttpStatusCode.OK, r.status)
        val o = json(text)
        assertEquals(setOf("entries", "source", "licence_url"), o.keys)
        assertEquals(FakeDictionarySource.SOURCE_ID, o["source"]!!.jsonPrimitive.content)
        assertEquals(FakeDictionarySource.LICENCE_URL, o["licence_url"]!!.jsonPrimitive.content)
        val entries = o["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        assertEquals(setOf("headword", "pos", "definition", "example", "synonyms"), entries[0].keys)
        assertEquals("verb", entries[0]["pos"]!!.jsonPrimitive.content)
        assertEquals("Run home.", entries[0]["example"]!!.jsonPrimitive.content)
        assertEquals("sprint", entries[0]["synonyms"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(JsonNull, entries[1]["example"], "a missing example is an explicit null")
    }

    @Test
    fun `the word is trimmed and case-folded before the lookup`() = testApp(services) {
        val (r, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("  Run ")) { authed() }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(listOf("en" to "run"), source.calls)
    }

    @Test
    fun `an unknown word is 404 not_found`() = testApp(services) {
        val (r, text) = contractCall(HttpMethod.Post, "/v1/dictionary", body("zzzq")) { authed() }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("not_found", json(text)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `two words, a 65-char word, symbols, another lang or a bad body are 400`() = testApp(services) {
        val bad = listOf(
            body("two words"), body("a".repeat(65)), body("   "), body("run1"), body("<b>"), body("run", "fr"),
            """{"word":"run"}""", """{"word":"run","lang":"en"""",
        )
        for (b in bad) {
            val (r, text) = contractCall(HttpMethod.Post, "/v1/dictionary", b, requestMustBeValid = false) { authed() }
            assertEquals(HttpStatusCode.BadRequest, r.status, b)
            assertEquals("bad_request", json(text)["error"]!!.jsonPrimitive.content)
        }
        assertTrue(source.calls.isEmpty())
        val (ok, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("rock-'n'-roll")) { authed() }
        assertEquals(HttpStatusCode.NotFound, ok.status, "letters, apostrophe and hyphen form one token")
        val (max, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("a".repeat(64))) { authed() }
        assertEquals(HttpStatusCode.NotFound, max.status)
    }

    @Test
    fun `no source configured is 503 dictionary_unavailable`() = testApp(services.copy(dictionary = null)) {
        val (r, text) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("dictionary_unavailable", json(text)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failing source is 503 and is not cached`() = testApp(services) {
        source.down = true
        val (r, text) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("dictionary_unavailable", json(text)["error"]!!.jsonPrimitive.content)
        source.down = false
        val (again, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed() }
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(2, source.calls.size)
    }

    @Test
    fun `no auth is 401`() = testApp(services) {
        val (r, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run"), requestMustBeValid = false)
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val (b, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run"), requestMustBeValid = false) {
            header("X-Firebase-AppCheck", TestKeys.appCheckToken())
        }
        assertEquals(HttpStatusCode.Unauthorized, b.status)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `the second identical call, from any uid, does not reach the source`() = testApp(services) {
        contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed("uid-a") }
        val (r, text) = contractCall(HttpMethod.Post, "/v1/dictionary", body("RUN")) { authed("uid-b") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(2, json(text)["entries"]!!.jsonArray.size)
        contractCall(HttpMethod.Post, "/v1/dictionary", body("zzzq")) { authed("uid-a") }
        contractCall(HttpMethod.Post, "/v1/dictionary", body("zzzq")) { authed("uid-b") }
        assertEquals(listOf("en" to "run", "en" to "zzzq"), source.calls, "found and not-found answers are cached")
    }

    @Test
    fun `a cached answer expires after 24 h`() = testApp(services) {
        contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed() }
        clock.advance(Duration.ofHours(24).minusSeconds(1))
        contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed() }
        assertEquals(1, source.calls.size)
        clock.advance(Duration.ofSeconds(1))
        contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed() }
        assertEquals(2, source.calls.size)
    }

    @Test
    fun `the 61st lookup in a minute is 429 for that uid only`() = testApp(services) {
        repeat(60) {
            val (r, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed("uid-a") }
            assertEquals(HttpStatusCode.OK, r.status)
        }
        val (r, text) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed("uid-a") }
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertEquals("rate_limited", json(text)["error"]!!.jsonPrimitive.content)
        assertTrue(r.headers["Retry-After"]!!.toLong() >= 1)
        val (other, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed("uid-b") }
        assertEquals(HttpStatusCode.OK, other.status)
    }

    @Test
    fun `account delete drops the uid's dictionary rate bucket`() = testApp(billingServices(base = services)) {
        repeat(61) { contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed("uid-a") } }
        val (d, dt) = contractCall(HttpMethod.Post, "/v1/account/delete", """{"client_req_id":"d1"}""") { authed("uid-a") }
        assertEquals(HttpStatusCode.OK, d.status, dt)
        val (r, _) = contractCall(HttpMethod.Post, "/v1/dictionary", body("run")) { authed("uid-a") }
        assertEquals(HttpStatusCode.OK, r.status)
    }
}
