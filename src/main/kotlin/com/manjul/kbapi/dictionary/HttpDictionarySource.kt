package com.manjul.kbapi.dictionary

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The Free Dictionary API (freedictionaryapi.com, OD-11): `GET <baseUrl>/<lang>/<word>?translations=false`, with
 * [baseUrl] from `DICTIONARY_SOURCE_URL` (`https://freedictionaryapi.com/api/v1/entries`). It is wired only when
 * that variable is set, which waits for the source terms check (Sprint 5 open question 4).
 *
 * The mapping follows a real captured answer (see `HttpDictionarySourceTest`): `{word, entries: [{language: {code},
 * partOfSpeech, senses: [{definition, examples, synonyms}], synonyms}], source: {url, license: {name, url}}}`.
 * Each top-level sense of an entry in [lang] becomes one [DictionaryEntry]: the first example, and the sense's
 * synonyms or else the entry's. Subsenses, quotes, forms and pronunciations are dropped. A 404 or no sense is
 * [Lookup.NotFound]; anything else throws [DictionarySourceException].
 *
 * Only the word and the language are sent: no uid, token, cookie or client header. Bodies are never logged.
 */
class HttpDictionarySource(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : DictionarySource {
    private val base = baseUrl.trimEnd('/')

    override suspend fun lookup(word: String, lang: String): Lookup {
        val uri = URI("$base/${enc(lang)}/${enc(word)}?translations=false")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw DictionarySourceException("io")
        }
        return when (response.statusCode()) {
            200 -> map(response.body(), word, lang)
            404 -> Lookup.NotFound
            else -> throw DictionarySourceException("http_${response.statusCode()}")
        }
    }

    private fun map(body: String, word: String, lang: String): Lookup {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw DictionarySourceException("parse")
        val licenceUrl = ((root["source"] as? JsonObject)?.get("license") as? JsonObject)?.str("url")
            ?: throw DictionarySourceException("parse")
        val headword = root.str("word") ?: word
        val entries = (root["entries"] as? JsonArray ?: throw DictionarySourceException("parse"))
            .mapNotNull { it as? JsonObject }
            .filter { (it["language"] as? JsonObject)?.str("code") == lang }
            .flatMap { entry ->
                val pos = entry.str("partOfSpeech").orEmpty()
                val entrySynonyms = entry.strings("synonyms")
                (entry["senses"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { sense ->
                    val definition = sense.str("definition")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    DictionaryEntry(
                        headword = headword,
                        pos = pos,
                        definition = definition,
                        example = sense.strings("examples").firstOrNull(),
                        synonyms = sense.strings("synonyms").ifEmpty { entrySynonyms }.take(MAX_SYNONYMS),
                    )
                }
            }
            .take(MAX_ENTRIES)
        return if (entries.isEmpty()) Lookup.NotFound else Lookup.Found(entries, SOURCE_ID, licenceUrl)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { e: JsonElement ->
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        const val SOURCE_ID = "freedictionaryapi"
        const val USER_AGENT = "kb-api-dictionary/1"
        const val MAX_ENTRIES = 10
        const val MAX_SYNONYMS = 5
    }
}
