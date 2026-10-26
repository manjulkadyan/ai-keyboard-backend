package com.manjul.kbapi.evals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradersTest {

    @Test
    fun `keepsTokens needs every token verbatim`() {
        assertTrue(Graders.keepsTokens("see https://x.y at 5pm", listOf("https://x.y", "5pm")))
        assertFalse(Graders.keepsTokens("see the link at 5pm", listOf("https://x.y", "5pm")))
        assertTrue(Graders.keepsTokens("anything", emptyList()))
        assertTrue(Graders.keepsTokens("ok 🎉 @sam", listOf("🎉", "@sam")))
    }

    @Test
    fun `noPreface rejects Here, Sure, quotes and a label colon`() {
        assertTrue(Graders.noPreface("I'm running late, be there in 10 mins."))
        assertTrue(Graders.noPreface("Meeting at 5:30 in room 2."))
        assertFalse(Graders.noPreface("Here is the rewritten message: I'm late."))
        assertFalse(Graders.noPreface("Sure! I'm running late."))
        assertFalse(Graders.noPreface("\"I'm running late.\""))
        assertFalse(Graders.noPreface("“Llego tarde.”"))
        assertFalse(Graders.noPreface("Formal version:\nI will be late."))
        assertFalse(Graders.noPreface("Rewritten: I will be late."))
        assertFalse(Graders.noPreface("   "))
        assertTrue(Graders.noPreface("Hereford is lovely in spring."))
    }

    @Test
    fun `lengthRatioOk by mode`() {
        val input = "I just wanted to let you know that I will be late tonight."
        assertTrue(Graders.lengthRatioOk("shorter", input, "I'll be late tonight."))
        assertFalse(Graders.lengthRatioOk("shorter", input, input))
        assertTrue(Graders.lengthRatioOk("longer", "Thanks for the help.", "Thank you so much for your help, I really appreciate it."))
        assertFalse(Graders.lengthRatioOk("longer", "Thanks for the help.", "Thanks for help."))
        assertTrue(Graders.lengthRatioOk("fix", "im late", "I'm late."))
        assertFalse(Graders.lengthRatioOk("fix", "im late", "Roses are red, violets are blue, here is a poem just for you."))
        assertFalse(Graders.lengthRatioOk("formal", input, "OK"))
        assertFalse(Graders.lengthRatioOk("fix", "", "anything"))
    }

    @Test
    fun `scriptMatches follows the language and the script setting`() {
        assertTrue(Graders.scriptMatches("hi", null, "कल मीटिंग में आ जाना"))
        assertFalse(Graders.scriptMatches("hi", null, "kal meeting mein aa jana"))
        assertTrue(Graders.scriptMatches("hi", "roman", "kal meeting mein aa jana"))
        assertFalse(Graders.scriptMatches("ur", "roman", "کل ملتے ہیں"))
        assertTrue(Graders.scriptMatches("ur", "native", "کل ملتے ہیں @ali https://x.y"))
        assertTrue(Graders.scriptMatches("ar", null, "مبروك على الوظيفة الجديدة 🎉"))
        assertFalse(Graders.scriptMatches("en", null, "مبروك على الوظيفة الجديدة"))
        assertTrue(Graders.scriptMatches("es", "native", "¿Qué tal estás?"))
        assertTrue(Graders.scriptMatches("ar", null, "🎉👏 123"))
    }

    @Test
    fun `dominantScript reads the input's writing system`() {
        assertEquals(Graders.Script.LATIN, Graders.dominantScript("yaar kal milte hain"))
        assertEquals(Graders.Script.ARABIC, Graders.dominantScript("کل ملتے ہیں @ali"))
        assertEquals(Graders.Script.DEVANAGARI, Graders.dominantScript("कल मिलते हैं"))
        assertNull(Graders.dominantScript("🎉 123"))
    }

    @Test
    fun `outputLang catches a wholesale wrong language`() {
        assertTrue(Graders.outputLang("es", null, "¿Nos vemos mañana en la casa de Ana? Yo llevo el pastel."))
        assertFalse(Graders.outputLang("es", null, "Are we meeting tomorrow at Ana's house? I will bring the cake."))
        assertTrue(Graders.outputLang("en", null, "Are we meeting tomorrow at Ana's house? I will bring the cake."))
        assertTrue(Graders.outputLang("ur", null, "کل ملتے ہیں، تم کہاں ہو؟"))
        assertFalse(Graders.outputLang("ur", null, "هل يمكنك أن تتصل بي عندما تصل إلى المنزل؟"))
        assertTrue(Graders.outputLang("ar", null, "هل يمكنك أن تتصل بي عندما تصل إلى المنزل؟"))
        assertTrue(Graders.outputLang("hi", "roman", "yaar kal meeting hai, please time pe aana"))
        assertFalse(Graders.outputLang("hi", "roman", "Hey, the meeting is tomorrow, please be on time and bring the notes."))
        assertFalse(Graders.outputLang("hi", null, "kal meeting hai"))
    }

    @Test
    fun `jsonShape per prompt`() {
        assertTrue(Graders.jsonShape("rewrite@1", "I'm late."))
        assertFalse(Graders.jsonShape("rewrite@1", """{"text":"I'm late."}"""))
        assertFalse(Graders.jsonShape("rewrite@1", "  "))

        assertTrue(Graders.jsonShape("polish_translate@1", """{"polished":"Hi.","translation":"Hola.","source_lang_detected":"en"}"""))
        assertFalse(Graders.jsonShape("polish_translate@1", """{"polished":"Hi.","translation":"Hola."}"""))
        assertFalse(Graders.jsonShape("polish_translate@1", """{"polished":"","translation":"Hola.","source_lang_detected":"en"}"""))
        assertFalse(Graders.jsonShape("polish_translate@1", "Hi. / Hola."))

        assertTrue(Graders.jsonShape("multi_tone@1", """{"variants":[{"mode":"formal","text":"Hello."},{"mode":"friendly","text":"Hey!"}]}"""))
        assertFalse(Graders.jsonShape("multi_tone@1", """{"variants":[]}"""))
        assertFalse(Graders.jsonShape("multi_tone@1", """{"variants":[{"mode":"formal"}]}"""))
        assertFalse(Graders.jsonShape("unknown@1", "x"))
    }
}
