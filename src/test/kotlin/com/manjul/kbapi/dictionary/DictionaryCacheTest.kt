package com.manjul.kbapi.dictionary

import com.manjul.kbapi.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DictionaryCacheTest {
    /** A distinct letters-only word for [i]. */
    private fun word(i: Int): String = "w" + i.toString().map { 'a' + (it - '0') }.joinToString("")

    @Test
    fun `the cache holds at most 10 000 keys and drops the least recently used`() = runBlocking {
        val source = FakeDictionarySource(emptyMap())
        val service = DictionaryService(source, MutableClock())
        for (i in 0 until DictionaryService.MAX_KEYS) service.lookup(word(i), "en")
        assertEquals(DictionaryService.MAX_KEYS, service.cachedKeys())
        service.lookup(word(0), "en") // a hit: word(0) becomes the most recently used
        service.lookup("extraword", "en") // evicts word(1)
        assertEquals(DictionaryService.MAX_KEYS, service.cachedKeys())
        val before = source.calls.size
        service.lookup(word(0), "en")
        assertEquals(before, source.calls.size, "a recently used key stays")
        service.lookup(word(1), "en")
        assertEquals(before + 1, source.calls.size, "the least recently used key was dropped")
    }

    @Test
    fun `a source failure is Unavailable`() = runBlocking {
        val source = FakeDictionarySource(emptyMap()).apply { down = true }
        assertIs<Lookup.Unavailable>(DictionaryService(source, MutableClock()).lookup("run", "en"))
    }
}
