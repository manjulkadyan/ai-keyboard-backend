package com.manjul.kbapi.ai

/** One versioned system prompt from `prompts/<id>@<version>.md` (TRD §4.4.3). */
data class Prompt(val id: String, val version: Int, val system: String) {
    val ref: String get() = "$id@$version"

    override fun toString(): String = "Prompt($ref)"
}

/**
 * Loads the prompt files from the classpath (`/prompts/<ref>.md`, packaged from the repo's `prompts/`
 * directory). The whole file is the system text, sent unchanged so the prefix stays cacheable.
 * [get] takes `id@version` or a bare id, which resolves to the highest loaded version.
 */
class PromptRegistry(
    refs: List<String> = DEFAULT_REFS,
    load: (String) -> String? = { ref -> PromptRegistry::class.java.getResource("/prompts/$ref.md")?.readText() },
) {
    val all: List<Prompt> = refs.map { ref ->
        val (id, version) = parse(ref)
        val text = load(ref) ?: error("prompt file missing: $ref")
        Prompt(id, version, text.trim())
    }

    private val byRef = all.associateBy { it.ref }
    private val latest = all.groupBy { it.id }.mapValues { (_, ps) -> ps.maxBy { it.version } }

    fun get(id: String): Prompt =
        (if ('@' in id) byRef[id] else latest[id]) ?: throw IllegalArgumentException("unknown prompt")

    companion object {
        const val REWRITE = "rewrite"
        const val POLISH = "polish_translate"
        const val MULTI_TONE = "multi_tone"

        val DEFAULT_REFS = listOf("rewrite@1", "polish_translate@1", "multi_tone@1")

        private val Ref = Regex("^([a-z_]+)@([0-9]+)$")

        fun parse(ref: String): Pair<String, Int> {
            val m = Ref.matchEntire(ref) ?: throw IllegalArgumentException("bad prompt ref")
            return m.groupValues[1] to m.groupValues[2].toInt()
        }
    }
}
