package com.manjul.kbapi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The image is built from a partial copy of the repo. Everything `installDist` packages must be copied
 * in first, or the jar ships without it: `prompts/` is on the classpath via processResources, and a
 * missing prompt makes `PromptRegistry()` fail at startup.
 */
class DockerfileTest {
    private val lines = File("Dockerfile").readLines().map { it.trim() }

    private fun indexOf(predicate: (String) -> Boolean) = lines.indexOfFirst(predicate)

    @Test
    fun `prompts are copied before installDist`() {
        val copy = indexOf { it.startsWith("COPY") && Regex("""\bprompts\b""").containsMatchIn(it) }
        val install = indexOf { it.startsWith("RUN") && "installDist" in it }
        assertTrue(install >= 0, "Dockerfile has no installDist step")
        assertTrue(copy in 0 until install, "Dockerfile must COPY prompts/ before installDist")
    }

    @Test
    fun `sources are copied before installDist`() {
        val copy = indexOf { it.startsWith("COPY src") }
        val install = indexOf { it.startsWith("RUN") && "installDist" in it }
        assertTrue(copy in 0 until install)
    }
}
