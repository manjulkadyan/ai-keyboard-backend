package com.manjul.kbapi.routes

import com.manjul.kbapi.ApiException
import com.manjul.kbapi.Errors
import com.manjul.kbapi.Services
import com.manjul.kbapi.auth.principal
import com.manjul.kbapi.dictionary.DictionaryEntry
import com.manjul.kbapi.dictionary.Lookup
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The lookup body. [toString] never prints the word: it is user content (PC-10). */
@Serializable
data class DictionaryRequest(val word: String, val lang: String) {
    override fun toString(): String = "DictionaryRequest(lang=$lang)"
}

/**
 * The 200 body. Built by hand because [com.manjul.kbapi.KbJson] drops nulls, and the contract has
 * `example: null` when the source has no example.
 */
internal fun dictionaryBody(found: Lookup.Found): JsonObject = buildJsonObject {
    put("entries", buildJsonArray { found.entries.forEach { add(entryJson(it)) } })
    put("source", found.source)
    put("licence_url", found.licenceUrl)
}

private fun entryJson(e: DictionaryEntry): JsonObject = buildJsonObject {
    put("headword", e.headword)
    put("pos", e.pos)
    put("definition", e.definition)
    put("example", e.example?.let(::JsonPrimitive) ?: JsonNull)
    put("synonyms", buildJsonArray { e.synonyms.forEach { add(it) } })
}

/** One token: letters (any script), `'` and `-`. */
private val WordPattern = Regex("[\\p{L}\\p{M}'’-]+")

/** The languages served in Sprint 5. */
private val Languages = setOf("en")
private const val MAX_WORD_CHARS = 64

/**
 * `POST /v1/dictionary` `{word, lang}` (Sprint 5 Task 3). The word is trimmed and lower-cased, and must be 1 to 64
 * chars and one token; `lang` must be `en`. Otherwise 400 `bad_request`. Rate limit: [Services.dictionaryLimiter]
 * (60 a minute per uid) -> 429. No source configured (`DICTIONARY_SOURCE_URL` unset) or a failing source -> 503
 * `dictionary_unavailable`. Unknown word -> 404 `not_found`. The word never reaches a log line.
 */
fun Route.dictionaryRoutes(services: Services) {
    post("/v1/dictionary") {
        val body = call.receive<DictionaryRequest>()
        val word = body.word.trim().lowercase()
        if (word.isEmpty() || word.length > MAX_WORD_CHARS || !WordPattern.matches(word) || body.lang !in Languages) {
            throw ApiException(Errors.badRequest())
        }
        val byUid = services.dictionaryLimiter.tryAcquire(call.principal.uid)
        if (!byUid.allowed) throw ApiException(Errors.rateLimited(byUid.retryAfterSeconds))
        val service = services.dictionaryService() ?: throw ApiException(Errors.dictionaryUnavailable())
        when (val r = service.lookup(word, body.lang)) {
            is Lookup.Found -> call.respond(dictionaryBody(r))
            Lookup.NotFound -> throw ApiException(Errors.notFound())
            Lookup.Unavailable -> throw ApiException(Errors.dictionaryUnavailable())
        }
    }
}
