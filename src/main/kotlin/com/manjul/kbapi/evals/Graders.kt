package com.manjul.kbapi.evals

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * Deterministic graders (decision 15). Each takes model output and returns pass or fail; none of them
 * calls a model. The optional LLM judge lives in [Judge].
 */
object Graders {
    enum class Script { LATIN, DEVANAGARI, ARABIC }

    // ---- preservation ----

    /** Every token (name, number, link, @mention, emoji) appears verbatim, after NFC normalisation. */
    fun keepsTokens(output: String, mustKeep: List<String>): Boolean {
        val out = nfc(output)
        return mustKeep.all { nfc(it) in out }
    }

    // ---- preface ----

    private val Quotes = setOf('"', '\'', '“', '”', '‘', '’', '«', '»', '„', '「', '`')
    private val Prefaces = listOf("here", "sure", "certainly", "of course", "claro", "aquí tienes", "aquí está", "por supuesto")
    private val LabelColon = Regex("^[\\p{L}\\p{M} ]{1,30}[:：](\\s|$)")

    /**
     * False when the output opens like a chat answer instead of the message itself: a leading "Here…" or
     * "Sure…" (and a few Spanish equivalents), a leading quote mark, or a first line that is a label with a
     * colon ("Formal version:", "Rewritten: …"). Times like "5:30" are fine.
     */
    fun noPreface(output: String): Boolean {
        val t = output.trimStart()
        if (t.isBlank()) return false
        if (t.first() in Quotes) return false
        val first = t.lineSequence().first().trim()
        if (first.endsWith(':') || first.endsWith('：')) return false
        if (LabelColon.containsMatchIn(first)) return false
        val lower = first.lowercase()
        return Prefaces.none { p -> lower.startsWith(p) && (lower.length == p.length || !lower[p.length].isLetter()) }
    }

    // ---- length ----

    /** Output/input length in code points: `shorter` ≤ 0.9×, `longer` ≥ 1.1×, every other mode 0.5–2.0×. */
    fun lengthRatioOk(mode: String, input: String, output: String): Boolean {
        val inLen = input.trim().let { it.codePointCount(0, it.length) }
        if (inLen == 0) return false
        val outLen = output.trim().let { it.codePointCount(0, it.length) }
        val r = outLen.toDouble() / inLen
        return when (mode) {
            "shorter" -> r <= 0.9
            "longer" -> r >= 1.1
            else -> r in 0.5..2.0
        }
    }

    // ---- script and language ----

    fun nativeScript(lang: String): Script = when (lang.substringBefore('-').lowercase()) {
        "hi", "mr", "ne" -> Script.DEVANAGARI
        "ar", "ur", "fa" -> Script.ARABIC
        else -> Script.LATIN
    }

    /** `roman` means Latin letters; anything else (`native`, `match_input`, null) means [lang]'s own script. */
    fun expectedScript(lang: String, script: String?): Script =
        if (script == "roman") Script.LATIN else nativeScript(lang)

    /** At least 60% of the output's letters (links, @mentions and #hashtags left out) are in the expected script. */
    fun scriptMatches(lang: String, script: String?, output: String): Boolean {
        val counts = scriptCounts(output)
        if (counts.total == 0) return true
        return counts.of(expectedScript(lang, script)) >= 0.6 * counts.total
    }

    /** The script most letters are in, or null when the text has no letters in a [Script]. */
    fun dominantScript(text: String): Script? {
        val counts = scriptCounts(text)
        return Script.entries.filter { counts.of(it) > 0 }.maxByOrNull { counts.of(it) }
    }

    private val Urls = Regex("https?://\\S+|www\\.\\S+")
    private val Handles = Regex("[@#][\\p{L}\\p{N}_.]+")

    /** Letter counts per [Script]; [total] also counts letters of any other script. */
    private class ScriptCounts(private val byScript: Map<Script, Int>, val total: Int) {
        fun of(s: Script): Int = byScript[s] ?: 0
    }

    private fun scriptCounts(text: String): ScriptCounts {
        val stripped = Handles.replace(Urls.replace(text, " "), " ")
        val counts = HashMap<Script, Int>()
        var total = 0
        var i = 0
        while (i < stripped.length) {
            val cp = stripped.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            total++
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> Script.LATIN
                Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
                Character.UnicodeScript.ARABIC -> Script.ARABIC
                else -> null
            }?.let { counts.merge(it, 1, Int::plus) }
        }
        return ScriptCounts(counts, total)
    }

    private val UrduMarks = "ٹڈڑںےۓہھکگیپچژ".toSet()
    private val ArabicMarks = "يكةىه".toSet()

    private val Stopwords: Map<String, Set<String>> = mapOf(
        "en" to setOf(
            "the", "is", "are", "and", "to", "you", "of", "for", "it", "that", "with", "this", "i", "my", "will",
            "can", "be", "have", "was", "we", "your", "me", "please", "at", "on", "in", "not", "what", "how", "do",
            "just", "so", "but", "if", "about", "there", "would", "could", "our", "they", "am",
        ),
        "es" to setOf(
            "el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "es", "por", "para", "con", "te", "lo",
            "mi", "se", "del", "al", "muy", "pero", "más", "está", "estás", "nos", "yo", "tu", "qué", "hoy",
            "mañana", "gracias", "hola", "como", "cómo", "tengo", "puedes", "vamos", "estoy",
        ),
        // Roman Hindi and Roman Urdu share most function words.
        "hu" to setOf(
            "hai", "hain", "nahi", "nahin", "kya", "mein", "mujhe", "tum", "aap", "ap", "ho", "ka", "ki", "ke",
            "ko", "se", "bhi", "yaar", "yar", "kal", "aaj", "kar", "karo", "raha", "rahi", "rahe", "hum", "tha",
            "thi", "acha", "accha", "theek", "bhai", "hun", "hoon", "kab", "kahan", "kaise", "jaldi", "abhi",
            "sab", "wala", "yeh", "ye", "woh", "wo", "liye", "mera", "meri", "tera", "apna", "batao", "bata",
        ),
    )

    private val Word = Regex("[\\p{L}]+")

    /**
     * The output reads as [expectLang] in the expected script: [scriptMatches] first; then Urdu vs Arabic by
     * their distinct letters, and en / es / Roman Hindi-Urdu by function words. Code-switched text passes
     * unless another language clearly dominates. Languages without a word list pass on script alone.
     */
    fun outputLang(expectLang: String, script: String?, output: String): Boolean {
        if (!scriptMatches(expectLang, script, output)) return false
        val lang = expectLang.substringBefore('-').lowercase()
        return when (expectedScript(lang, script)) {
            Script.ARABIC -> {
                val urdu = output.count { it in UrduMarks }
                val arabic = output.count { it in ArabicMarks }
                when (lang) {
                    "ur" -> urdu >= arabic
                    "ar" -> arabic >= urdu
                    else -> true
                }
            }
            Script.LATIN -> {
                val key = when (lang) {
                    "en", "es" -> lang
                    "hi", "ur" -> "hu"
                    else -> return true
                }
                val words = Word.findAll(output.lowercase()).map { it.value }.toList()
                val scores = Stopwords.mapValues { (_, set) -> words.count { it in set } }
                val expected = scores.getValue(key)
                val maxOther = scores.filterKeys { it != key }.values.max()
                !(maxOther >= 2 && maxOther > 2 * expected)
            }
            Script.DEVANAGARI -> true
        }
    }

    // ---- JSON shape ----

    /**
     * `rewrite`: plain non-blank text, not a JSON object. `polish_translate`: exactly `polished`,
     * `translation`, `source_lang_detected`, all strings, the first two non-blank. `multi_tone`: exactly
     * `{"variants":[…]}` with 1–3 objects of exactly non-blank `mode` and `text`.
     */
    fun jsonShape(prompt: String, output: String): Boolean = when (prompt.substringBefore('@')) {
        "rewrite" -> output.isNotBlank() && parseObject(output.trim()) == null
        "polish_translate" -> {
            val o = parseObject(output.trim())
            o != null && o.keys == setOf("polished", "translation", "source_lang_detected") &&
                o.str("polished")?.isNotBlank() == true && o.str("translation")?.isNotBlank() == true &&
                o.str("source_lang_detected") != null
        }
        "multi_tone" -> variants(output) != null
        else -> false
    }

    /** The multi-tone variants as (mode, text) pairs, or null when the shape is wrong. */
    fun variants(output: String): List<Pair<String, String>>? {
        val o = parseObject(output.trim()) ?: return null
        if (o.keys != setOf("variants")) return null
        val arr = o["variants"] as? JsonArray ?: return null
        if (arr.size !in 1..3) return null
        return arr.map { v ->
            val vo = v as? JsonObject ?: return null
            if (vo.keys != setOf("mode", "text")) return null
            val mode = vo.str("mode")?.takeIf { it.isNotBlank() } ?: return null
            val text = vo.str("text")?.takeIf { it.isNotBlank() } ?: return null
            mode to text
        }
    }

    /** The polish fields (polished, translation, source_lang_detected), or null when the shape is wrong. */
    fun polishFields(output: String): Triple<String, String, String>? {
        if (!jsonShape("polish_translate", output)) return null
        val o = parseObject(output.trim())!!
        return Triple(o.str("polished")!!, o.str("translation")!!, o.str("source_lang_detected")!!)
    }

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)
}
