package com.manjul.kbapi.dictionary

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Maps a real Free Dictionary API (freedictionaryapi.com, OD-11) answer. The fixture
 * `dictionary/freedictionaryapi-v1-hello.json` is the body of a real capture of
 * `GET https://freedictionaryapi.com/api/v1/entries/en/hello?translations=false` (fetched 2026-10-10), committed at
 * https://github.com/Fristail27/vocab-bloom-hub/blob/ff4e2e99606ee5e8b3f15dab664339dc8c358a2d/apps/site/public/comparison-responses/freedictionaryapi/v1/plain/hello.json
 * (MIT repo). The JSON structure, keys, types, headword, parts of speech, tags, languages and the `source` block
 * are kept as captured. The definition, example, quote and pronunciation text is replaced with `Fixture …`
 * placeholders and the synonym lists are cut to three, so no CC BY-SA Wiktionary text is committed (R-08).
 * The 404 for an unknown word follows the public client at
 * https://github.com/jawuanlewis/glimpse-ext/blob/HEAD/utils/api.js (`if (response.status === 404) return NOT_FOUND`).
 */
class HttpDictionarySourceTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, List<String>>>>()
    private val fixture = javaClass.getResource("/dictionary/freedictionaryapi-v1-hello.json")!!.readText()
    private var helloBody = fixture
    private var status = 200

    private fun source() = HttpDictionarySource("http://127.0.0.1:${server.address.port}/api/v1/entries")

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests += ex.requestURI.rawPath + "?" + ex.requestURI.rawQuery to ex.requestHeaders.toMap()
            val (code, body) = when {
                ex.requestURI.rawPath == "/api/v1/entries/en/hello" -> status to helloBody
                else -> 404 to """{"error":"x"}"""
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `the captured answer maps to one entry per top-level sense`() = runBlocking {
        val found = assertIs<Lookup.Found>(source().lookup("hello", "en"))
        assertEquals(HttpDictionarySource.SOURCE_ID, found.source)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", found.licenceUrl)
        assertEquals(listOf("interjection", "interjection", "interjection", "interjection", "interjection", "noun", "verb"),
            found.entries.map { it.pos })
        assertTrue(found.entries.all { it.headword == "hello" })
        val first = found.entries.first()
        assertEquals("Fixture definition 1.", first.definition)
        assertEquals("Fixture example 1.", first.example)
        assertEquals(listOf("g'day", "hey", "hi"), first.synonyms, "empty sense synonyms fall back to the entry's")
        assertEquals("(colloquial) Fixture definition 4.", found.entries[3].definition)
        assertEquals(listOf("greeting"), found.entries[5].synonyms)
        val verb = found.entries.last()
        assertEquals("(transitive) Fixture definition 7.", verb.definition)
        assertNull(verb.example)
        assertEquals(emptyList(), verb.synonyms)
    }

    @Test
    fun `the request carries the word and lang only, with no user identifier`() = runBlocking {
        source().lookup("hello", "en")
        source().lookup("rock'n'roll", "en")
        assertEquals("/api/v1/entries/en/hello?translations=false", requests[0].first)
        assertEquals("/api/v1/entries/en/rock%27n%27roll?translations=false", requests[1].first)
        val headers = requests[0].second.keys.map { it.lowercase() }.toSet()
        assertTrue("authorization" !in headers && "x-firebase-appcheck" !in headers && "cookie" !in headers, "$headers")
        assertEquals(listOf(HttpDictionarySource.USER_AGENT), requests[0].second.entries.first { it.key.equals("User-Agent", true) }.value)
    }

    @Test
    fun `404 and an answer without senses in the asked language are NotFound`() = runBlocking {
        assertIs<Lookup.NotFound>(source().lookup("zzzq", "en"))
        helloBody = """{"word":"hello","entries":[],"source":{"url":"u","license":{"name":"n","url":"l"}}}"""
        assertIs<Lookup.NotFound>(source().lookup("hello", "en"))
        val other = Json.parseToJsonElement(fixture).jsonObject.let { o ->
            val entries = o["entries"]!!.jsonArray.map { e ->
                JsonObject(e.jsonObject + ("language" to Json.parseToJsonElement("""{"code":"de","name":"German"}""")))
            }
            JsonObject(o + ("entries" to JsonArray(entries))).toString()
        }
        helloBody = other
        assertIs<Lookup.NotFound>(source().lookup("hello", "en"))
    }

    @Test
    fun `a server error, a body that is not the captured shape or a missing licence throws`() = runBlocking {
        status = 500
        assertFailsWith<DictionarySourceException> { source().lookup("hello", "en") }
        status = 200
        helloBody = "<html>"
        assertFailsWith<DictionarySourceException> { source().lookup("hello", "en") }
        helloBody = JsonObject(Json.parseToJsonElement(fixture).jsonObject - "source").toString()
        assertFailsWith<DictionarySourceException> { source().lookup("hello", "en") }
        Unit
    }

    @Test
    fun `entries and synonyms are capped`() = runBlocking {
        val o = Json.parseToJsonElement(fixture).jsonObject
        val many = JsonArray(List(5) { o["entries"]!!.jsonArray }.flatten())
        helloBody = JsonObject(o + ("entries" to many)).toString()
        val found = assertIs<Lookup.Found>(source().lookup("hello", "en"))
        assertEquals(HttpDictionarySource.MAX_ENTRIES, found.entries.size)
        assertTrue(found.entries.all { it.synonyms.size <= HttpDictionarySource.MAX_SYNONYMS })
    }

    @Test
    fun `an exception never carries the word`() = runBlocking {
        server.stop(0)
        val e = assertFailsWith<DictionarySourceException> { source().lookup("zyxwordleak", "en") }
        assertTrue("zyxwordleak" !in e.toString())
        assertNull(e.cause)
    }
}
