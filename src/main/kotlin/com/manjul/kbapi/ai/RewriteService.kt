package com.manjul.kbapi.ai

import com.manjul.kbapi.ApiException
import com.manjul.kbapi.Config
import com.manjul.kbapi.Errors
import com.manjul.kbapi.limits.Limits
import com.manjul.kbapi.routes.RewriteRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RewriteVariant(val mode: String, val text: String) {
    override fun toString(): String = "RewriteVariant($mode)"
}

@Serializable
data class RewriteVariants(
    val variants: List<RewriteVariant>,
    @SerialName("quota_left") val quotaLeft: Int,
    val prompt: String,
)

/**
 * `POST /v1/rewrite` (TRD §5.3, PRD FR-15): one mode streams plain text over SSE with `rewrite@1`;
 * `modes` (2 or 3) returns JSON variants from one `multi_tone@1` call, counted as one action.
 */
class RewriteService(
    private val config: Config,
    private val prompts: PromptRegistry,
    private val limits: Limits,
    private val runner: AiRunner,
) {
    suspend fun handle(call: ApplicationCall, body: RewriteRequest) {
        validate(body)
        val multi = body.modes != null
        val req = if (multi) multiRequest(config, prompts, body) else singleRequest(config, prompts, body)
        val prompt = prompts.get(if (multi) PromptRegistry.MULTI_TONE else PromptRegistry.REWRITE)
        val ai = AiCall(call, limits, prompt, req.model)
        val admission = ai.admit(body.text.length, body.clientReqId)

        coroutineScope {
            if (multi) {
                val variants = try {
                    val (text, _) = runner.collect(this, req, ai)
                    parseVariants(text, body.modes!!) ?: throw ApiException(Errors.internal())
                } catch (e: Throwable) {
                    ai.refund(body.clientReqId)
                    throw e
                }
                call.respond(RewriteVariants(variants, admission.leftToday, prompt.ref))
            } else {
                val opened = try {
                    runner.open(this, req, ai)
                } catch (e: Throwable) {
                    ai.refund(body.clientReqId)
                    throw e
                }
                SseRelay.respond(call, opened, runner, ai) { _, done ->
                    JsonObject(
                        mapOf(
                            "quota_left" to JsonPrimitive(admission.leftToday),
                            "trimmed" to JsonPrimitive(done.stopReason == StopReasons.MAX_TOKENS),
                            "prompt" to JsonPrimitive(prompt.ref),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val MAX_TOKENS = 1024L
        val MODES = setOf("fix", "formal", "friendly", "shorter", "longer", "emojify", "translate")
        private val Lang = Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$")

        val MULTI_SCHEMA: String = """
            {"type":"object","additionalProperties":false,"required":["variants"],
             "properties":{"variants":{"type":"array","items":{"type":"object","additionalProperties":false,
               "required":["mode","text"],"properties":{"mode":{"type":"string"},"text":{"type":"string"}}}}}}
        """.trimIndent()

        /** Throws 400 `bad_request`; never echoes a value. */
        fun validate(body: RewriteRequest) {
            fun bad(): Nothing = throw ApiException(Errors.badRequest())
            if (body.mode !in MODES) bad()
            if (!Lang.matches(body.locale) || body.locale.length > 35) bad()
            if (body.text.isEmpty()) bad()
            if (body.clientReqId.isEmpty() || body.clientReqId.length > 64) bad()
            val modes = body.modes
            if (modes != null && (modes.size !in 2..3 || modes.any { it !in MODES } || modes.toSet().size != modes.size)) bad()
            val needsTarget = "translate" in (modes ?: listOf(body.mode))
            val target = body.targetLang
            if (needsTarget && target.isNullOrBlank()) bad()
            if (target != null && (!Lang.matches(target) || target.length > 35)) bad()
        }

        private fun target(body: RewriteRequest, modes: List<String>) =
            body.targetLang?.takeIf { "translate" in modes }

        fun singleRequest(config: Config, prompts: PromptRegistry, body: RewriteRequest) = GatewayRequest(
            model = config.rewriteModel,
            system = prompts.get(PromptRegistry.REWRITE).system,
            user = UserTurn.rewrite(body.mode, body.locale, body.text, target(body, listOf(body.mode))),
            maxTokens = MAX_TOKENS,
            jsonSchema = null,
        )

        fun multiRequest(config: Config, prompts: PromptRegistry, body: RewriteRequest): GatewayRequest {
            val modes = body.modes ?: listOf(body.mode)
            return GatewayRequest(
                model = config.rewriteModel,
                system = prompts.get(PromptRegistry.MULTI_TONE).system,
                user = UserTurn.rewrite(modes.joinToString(","), body.locale, body.text, target(body, modes)),
                maxTokens = MAX_TOKENS,
                jsonSchema = MULTI_SCHEMA,
            )
        }

        /** One variant per requested mode, in request order; null when the JSON is unusable. */
        fun parseVariants(text: String, modes: List<String>): List<RewriteVariant>? {
            val arr = parseObjectOrNull(text)?.get("variants") as? JsonArray ?: return null
            val byMode = LinkedHashMap<String, String>()
            for (v in arr) {
                val o = v as? JsonObject ?: continue
                val mode = o["mode"].stringOrNull() ?: continue
                val t = o["text"].stringOrNull() ?: continue
                if (mode in modes && t.isNotBlank()) byMode.putIfAbsent(mode, t)
            }
            val out = modes.mapNotNull { m -> byMode[m]?.let { RewriteVariant(m, it) } }
            return out.takeIf { it.isNotEmpty() }
        }
    }
}
