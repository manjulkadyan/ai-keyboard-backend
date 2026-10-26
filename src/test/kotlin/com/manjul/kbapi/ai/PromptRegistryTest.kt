package com.manjul.kbapi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptRegistryTest {
    private val registry = PromptRegistry()

    private val rewriteFirst = "You edit short messages that someone typed on a phone keyboard."
    private val polishFirst = "You clean up speech-to-text transcripts and translate them."

    @Test
    fun `all three prompts load with their refs`() {
        assertEquals("rewrite@1", registry.get("rewrite").ref)
        assertEquals("polish_translate@1", registry.get("polish_translate").ref)
        assertEquals("multi_tone@1", registry.get("multi_tone").ref)
        assertEquals("rewrite@1", registry.get("rewrite@1").ref)
        assertEquals(setOf("rewrite@1", "polish_translate@1", "multi_tone@1"), registry.all.map { it.ref }.toSet())
    }

    @Test
    fun `each system starts with the TRD first sentence`() {
        assertTrue(registry.get("rewrite").system.startsWith(rewriteFirst))
        assertTrue(registry.get("multi_tone").system.startsWith(rewriteFirst))
        assertTrue(registry.get("polish_translate").system.startsWith(polishFirst))
    }

    @Test
    fun `each prefix is above the cache minimum`() {
        // About 4 characters per token for Latin text; the non-Latin examples only add tokens.
        for (p in registry.all) assertTrue(p.system.length / 4 >= 600, "${p.ref} is too short to cache")
    }

    @Test
    fun `polish prompt documents the script tag`() {
        val system = registry.get("polish_translate").system
        for (tag in listOf("<script>", "`roman`", "`native`", "`match_input`", "Devanagari")) {
            assertTrue(tag in system, "polish prompt misses $tag")
        }
    }

    @Test
    fun `unknown prompt fails`() {
        assertFailsWith<IllegalArgumentException> { registry.get("nope") }
    }
}
