package com.manjul.kbapi.dictionary

/**
 * One normalised sense. [headword] is the looked-up word, so [toString] prints the part of speech only: a query is
 * user content and must never reach a log line (PC-10).
 */
data class DictionaryEntry(
    val headword: String,
    val pos: String,
    val definition: String,
    val example: String?,
    val synonyms: List<String>,
) {
    override fun toString(): String = "DictionaryEntry(pos=$pos)"
}

/** The answer for one `(lang, word)`. */
sealed interface Lookup {
    data class Found(val entries: List<DictionaryEntry>, val source: String, val licenceUrl: String) : Lookup {
        override fun toString(): String = "Found(entries=${entries.size}, source=$source)"
    }

    data object NotFound : Lookup

    /** The source failed. [DictionaryService] answers this; a [DictionarySource] throws instead. */
    data object Unavailable : Lookup
}

/**
 * The upstream dictionary kb-api proxies (OD-11). The device never calls it; only kb-api does, and only with the
 * word and the language: no uid, token or other user identifier.
 */
fun interface DictionarySource {
    /** [Lookup.Found] or [Lookup.NotFound]. Throws [DictionarySourceException] when the source is down or broken. */
    suspend fun lookup(word: String, lang: String): Lookup
}

/**
 * A source failure. [reason] is a short code (`io`, `http_500`, `parse`); the message never carries the word and
 * no cause or stack trace is kept, so nothing request-derived can ride along.
 */
class DictionarySourceException(val reason: String) : RuntimeException(reason, null, false, false)
