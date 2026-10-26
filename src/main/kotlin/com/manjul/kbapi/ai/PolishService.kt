package com.manjul.kbapi.ai

import com.manjul.kbapi.ApiException
import com.manjul.kbapi.Config
import com.manjul.kbapi.Errors
import com.manjul.kbapi.limits.Limits
import com.manjul.kbapi.routes.PolishRequest
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `POST /v1/polish` (TRD §5.3, PRD FR-16) with `polish_translate@1`: the model's raw JSON streams as
 * `delta` text; `done` carries the parsed `polished`, `translation`, `source_lang_detected` and `quota_left`.
 * Unparseable JSON at the end is `event: error {"error":"internal"}`.
 */
class PolishService(
    private val config: Config,
    private val prompts: PromptRegistry,
    private val limits: Limits,
    private val runner: AiRunner,
) {
    suspend fun handle(call: ApplicationCall, body: PolishRequest) {
        validate(body)
        val req = request(config, prompts, body)
        val prompt = prompts.get(PromptRegistry.POLISH)
        val ai = AiCall(call, limits, prompt, req.model, config.trustedProxyHops)
        val admission = ai.admit(body.transcript.length, body.clientReqId)

        coroutineScope {
            val opened = try {
                runner.open(this, req, ai)
            } catch (e: Throwable) {
                ai.refund(body.clientReqId)
                throw e
            }
            SseRelay.respond(call, opened, runner, ai) { text, _ ->
                parse(text)?.let { p ->
                    JsonObject(
                        mapOf(
                            "polished" to JsonPrimitive(p.polished),
                            "translation" to JsonPrimitive(p.translation),
                            "source_lang_detected" to JsonPrimitive(p.sourceLangDetected),
                            "quota_left" to JsonPrimitive(admission.leftToday),
                            "prompt" to JsonPrimitive(prompt.ref),
                        ),
                    )
                }
            }
        }
    }

    data class Polished(val polished: String, val translation: String, val sourceLangDetected: String) {
        override fun toString(): String = "Polished(source=$sourceLangDetected)"
    }

    companion object {
        const val MAX_TOKENS = 2048L

        /** The request enum (FR-16 AC4, decision 12) to the `<script>` tag value the prompt documents. */
        val SCRIPTS = mapOf("MATCH_INPUT" to "match_input", "ROMAN" to "roman", "NATIVE" to "native")
        private val Lang = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")
        private val DetectedLang = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")

        val SCHEMA: String = """
            {"type":"object","additionalProperties":false,"required":["polished","translation","source_lang_detected"],
             "properties":{"polished":{"type":"string"},"translation":{"type":"string"},"source_lang_detected":{"type":"string"}}}
        """.trimIndent()

        fun validate(body: PolishRequest) {
            fun bad(): Nothing = throw ApiException(Errors.badRequest())
            if (body.transcript.isEmpty()) bad()
            if (!Lang.matches(body.targetLang) || body.targetLang.length > 35) bad()
            body.sourceLang?.let { if (!Lang.matches(it) || it.length > 35) bad() }
            if (body.script != null && body.script !in SCRIPTS) bad()
            if (body.clientReqId.isEmpty() || body.clientReqId.length > 64) bad()
        }

        fun request(config: Config, prompts: PromptRegistry, body: PolishRequest) = GatewayRequest(
            model = config.rewriteModel,
            system = prompts.get(PromptRegistry.POLISH).system,
            user = UserTurn.polish(body.transcript, body.sourceLang, body.targetLang, SCRIPTS[body.script ?: "MATCH_INPUT"]!!),
            maxTokens = MAX_TOKENS,
            jsonSchema = SCHEMA,
        )

        fun parse(text: String): Polished? {
            val o = parseObjectOrNull(text) ?: return null
            val polished = o["polished"].stringOrNull() ?: return null
            val translation = o["translation"].stringOrNull() ?: return null
            val lang = o["source_lang_detected"].stringOrNull()?.takeIf { DetectedLang.matches(it) && it.length <= 35 } ?: "und"
            return Polished(polished, translation, lang)
        }
    }
}
