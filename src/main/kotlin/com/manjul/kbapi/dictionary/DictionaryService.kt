package com.manjul.kbapi.dictionary

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Looks words up through [source] with an in-memory cache keyed by `(lang, word)`: answers (found and not found)
 * live for 24 h, at most [MAX_KEYS] keys, least recently used dropped first. The key is not tied to a uid, so one
 * user's lookup can serve another's. A source failure is [Lookup.Unavailable] and is not cached.
 *
 * The word is user content (PC-10): it is never logged. A failure logs the reason code only.
 */
class DictionaryService(private val source: DictionarySource, private val clock: Clock) {
    private class Cached(val lookup: Lookup, val atMs: Long)

    private val cache = object : LinkedHashMap<Pair<String, String>, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Cached>): Boolean =
            size > MAX_KEYS
    }

    /** [word] is already validated and normalised by the route. */
    suspend fun lookup(word: String, lang: String): Lookup {
        val key = lang to word
        val now = clock.millis()
        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && now - hit.atMs < TTL_MS) return hit.lookup
            if (hit != null) cache.remove(key)
        }
        val answer = try {
            source.lookup(word, lang)
        } catch (e: DictionarySourceException) {
            log.warn("dictionary source failed reason={}", e.reason)
            return Lookup.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("dictionary source failed reason={}", e::class.java.simpleName)
            return Lookup.Unavailable
        }
        if (answer == Lookup.Unavailable) return answer
        synchronized(cache) { cache[key] = Cached(answer, clock.millis()) }
        return answer
    }

    fun cachedKeys(): Int = synchronized(cache) { cache.size }

    companion object {
        const val MAX_KEYS = 10_000
        val TTL_MS: Long = Duration.ofHours(24).toMillis()
        private val log = LoggerFactory.getLogger("kbapi.dictionary")
    }
}
