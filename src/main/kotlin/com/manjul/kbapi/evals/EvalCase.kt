package com.manjul.kbapi.evals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One golden case, one line of `evals/cases/<prompt>.jsonl` (TRD §7.1 "AI evals"). Authored by us.
 * - [lang]: the language the input is written in (for polish, the dictation language).
 * - [mode] (rewrite) or [modes] (multi-tone, 2–3); [target]: the target language for translate and polish.
 * - [script]: `roman`, `native` or `match_input`. For polish it is sent as the request's script; for every
 *   prompt it is the writing system the output must use (null or `match_input` = the input's own script).
 * - [mustKeep]: tokens (names, numbers, links, @mentions, emoji) that must come back verbatim.
 * - [expectLang]: the output language (the target for translate and polish, else [lang]).
 * - [notes]: free text for reviewers; cases whose notes start with `injection` are prompt-injection cases.
 */
@Serializable
data class EvalCase(
    val id: String,
    val prompt: String,
    val lang: String,
    val mode: String? = null,
    val modes: List<String>? = null,
    val target: String? = null,
    val script: String? = null,
    val input: String,
    @SerialName("must_keep") val mustKeep: List<String> = emptyList(),
    @SerialName("expect_lang") val expectLang: String,
    val notes: String? = null,
) {
    val isInjection: Boolean get() = notes?.startsWith("injection") == true

    val promptId: String get() = prompt.substringBefore('@')
}

object EvalCases {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun parse(line: String): EvalCase = json.decodeFromString(EvalCase.serializer(), line)

    fun encode(case: EvalCase): String = json.encodeToString(EvalCase.serializer(), case)

    fun load(file: File): List<EvalCase> = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map(::parse)
}
