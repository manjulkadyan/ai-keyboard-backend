package com.manjul.kbapi.ai

/**
 * Builds the user turn. Every value is tag-wrapped and `<`/`>` are escaped to `&lt;`/`&gt;`,
 * so user text can never close its tag or open a new one; the prompts say how to read them.
 */
object UserTurn {
    fun escape(s: String): String = s.replace("<", "&lt;").replace(">", "&gt;")

    /** `<mode>…</mode><locale>…</locale>[<target_lang>…</target_lang>]<text>…</text>`. [mode] may be a comma list. */
    fun rewrite(mode: String, locale: String, text: String, target: String?): String = buildString {
        tag("mode", mode)
        tag("locale", locale)
        if (target != null) tag("target_lang", target)
        tag("text", text)
    }

    /** `<source_lang>…</source_lang><target_lang>…</target_lang><script>…</script><transcript>…</transcript>`. */
    fun polish(transcript: String, source: String?, target: String, script: String): String = buildString {
        tag("source_lang", source ?: "auto")
        tag("target_lang", target)
        tag("script", script)
        tag("transcript", transcript)
    }

    private fun StringBuilder.tag(name: String, value: String) {
        append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
    }
}
