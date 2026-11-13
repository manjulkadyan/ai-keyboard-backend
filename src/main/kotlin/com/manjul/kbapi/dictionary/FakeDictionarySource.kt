package com.manjul.kbapi.dictionary

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Canned answers for tests and local runs (`GATEWAY=fake` with `AUTH_MODE=dev`). Records every call in [calls];
 * [down] makes every call fail like an unreachable source.
 */
class FakeDictionarySource(private val entries: Map<Pair<String, String>, List<DictionaryEntry>>) : DictionarySource {
    val calls = CopyOnWriteArrayList<Pair<String, String>>()

    @Volatile var down = false

    override suspend fun lookup(word: String, lang: String): Lookup {
        calls += lang to word
        if (down) throw DictionarySourceException("fake_down")
        return entries[lang to word]?.let { Lookup.Found(it, SOURCE_ID, LICENCE_URL) } ?: Lookup.NotFound
    }

    companion object {
        const val SOURCE_ID = "fake"
        const val LICENCE_URL = "https://example.com/fake-dictionary-licence"

        /** One word for local runs. */
        fun demo() = FakeDictionarySource(
            mapOf(("en" to "run") to listOf(DictionaryEntry("run", "verb", "To move quickly on foot.", "Run home.", listOf("sprint")))),
        )
    }
}
