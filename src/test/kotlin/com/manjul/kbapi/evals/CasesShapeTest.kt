package com.manjul.kbapi.evals

import com.manjul.kbapi.ai.PromptRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CasesShapeTest {
    private val prompts = PromptRegistry()
    private val dir = File("evals/cases")
    private val refs = listOf("rewrite@1", "polish_translate@1", "multi_tone@1")
    private val langs = setOf("en", "es", "hi", "ur", "ar")

    private fun lines(ref: String): List<String> = File(dir, "$ref.jsonl").readLines().filter { it.isNotBlank() }

    @Test
    fun `each file has 300 parseable lines, 60 per language, unique ids`() {
        val allIds = HashSet<String>()
        for (ref in refs) {
            val lines = lines(ref)
            assertEquals(300, lines.size, "$ref line count")
            val cases = lines.mapIndexed { i, line ->
                try {
                    EvalCases.parse(line)
                } catch (e: Exception) {
                    fail("$ref line ${i + 1} does not parse")
                }
            }
            assertEquals(langs.associateWith { 60 }, cases.groupingBy { it.lang }.eachCount(), "$ref per-language counts")
            for (c in cases) {
                assertEquals(ref, c.prompt, "${c.id} prompt")
                assertTrue(allIds.add(c.id), "duplicate id ${c.id}")
            }
        }
    }

    @Test
    fun `every case is a valid production request and its tokens are in the input`() {
        for (ref in refs) {
            for (c in EvalCases.load(File(dir, "$ref.jsonl"))) {
                assertTrue(c.input.isNotBlank() && c.input.length <= 2000, "${c.id} input length")
                EvalRunner.request(c, "claude-haiku-5-5", prompts) // throws on an invalid mode, target or script
                for (t in c.mustKeep) assertTrue(t in c.input, "${c.id} must_keep token not in input")
                val translates = c.prompt == "polish_translate@1" || c.mode == "translate" || c.modes?.contains("translate") == true
                if (!translates) assertEquals(c.lang, c.expectLang, "${c.id} expect_lang")
                if (c.prompt == "multi_tone@1") assertTrue(c.modes != null && c.modes.size in 2..3, "${c.id} modes")
                if (c.prompt == "polish_translate@1") assertTrue(c.target != null, "${c.id} target")
            }
        }
    }

    @Test
    fun `each file has injection cases and Roman-script hi and ur cases`() {
        for (ref in refs) {
            val cases = EvalCases.load(File(dir, "$ref.jsonl"))
            assertTrue(cases.count { it.isInjection } >= 10, "$ref injection cases")
            for (lang in listOf("hi", "ur")) {
                val roman = cases.count { it.lang == lang && Graders.dominantScript(it.input) == Graders.Script.LATIN }
                assertTrue(roman >= 10, "$ref $lang Roman-script cases: $roman")
            }
        }
    }

    @Test
    fun `rewrite cases cover every mode`() {
        val modes = EvalCases.load(File(dir, "rewrite@1.jsonl")).mapNotNull { it.mode }.toSet()
        assertEquals(setOf("fix", "formal", "friendly", "shorter", "longer", "emojify", "translate"), modes)
    }

    /**
     * Guard only: the cases are authored by us. If the teardown work dirs exist, no case line may contain
     * one of their string-table entries (20+ chars). Nothing from them is printed or kept.
     */
    @Test
    fun `no case line contains a teardown string`() {
        val roots = listOf(File("../voicetranslator_work"), File("../voicetyping_work")).filter { it.isDirectory }
        assumeTrue(roots.isNotEmpty(), "teardown work dirs not present")
        val index = TeardownStrings.index(roots)
        assumeTrue(index.size > 0, "no string tables found")
        var hits = 0
        for (ref in refs) for (line in lines(ref)) if (index.matches(line)) hits++
        assertEquals(0, hits, "case lines matching a teardown string")
    }
}

/** Indexes string-table values by a fixed-length prefix so each case line is checked in one pass. */
internal class TeardownStrings private constructor(private val byPrefix: Map<String, List<String>>) {
    val size: Int get() = byPrefix.size

    fun matches(line: String): Boolean {
        val l = line.lowercase()
        for (i in 0..(l.length - KEY)) {
            val bucket = byPrefix[l.substring(i, i + KEY)] ?: continue
            if (bucket.any { l.startsWith(it, i) }) return true
        }
        return false
    }

    companion object {
        private const val MIN = 20
        private const val KEY = 12
        private val Entry = Regex("<string[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        fun index(roots: List<File>): TeardownStrings {
            val map = HashMap<String, MutableList<String>>()
            for (root in roots) {
                root.walkTopDown()
                    .filter { it.isFile && it.name == "strings.xml" && it.parentFile.name.startsWith("values") }
                    .forEach { f ->
                        for (m in Entry.findAll(f.readText())) {
                            val s = m.groupValues[1].replace("\\'", "'").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim().lowercase()
                            if (s.length >= MIN) map.getOrPut(s.substring(0, KEY)) { mutableListOf() }.add(s)
                        }
                    }
            }
            return TeardownStrings(map)
        }
    }
}
