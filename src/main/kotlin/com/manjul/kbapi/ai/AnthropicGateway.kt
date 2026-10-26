package com.manjul.kbapi.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.manjul.kbapi.limits.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import com.anthropic.models.messages.Model as AnthropicModel

/**
 * The Anthropic Messages API behind [ModelGateway] (TRD §4.4.2, §11.4):
 * - the system prompt is one text block with `cache_control` (the last stable block);
 * - thinking is adaptive with display left at its default (omitted); only text deltas are relayed;
 * - `output_config.effort` is always `low`, plus the JSON format when the request has a schema;
 * - non-Haiku models (Sonnet, Opus) go through the beta API with server-side fallback
 *   (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`); Haiku has none, so the
 *   step-up retry lives in [AiRunner].
 *
 * Every SDK failure becomes a [GatewayException] with a code only: upstream messages can echo the prompt.
 */
class AnthropicGateway(private val client: AnthropicClient) : ModelGateway {

    override fun stream(req: GatewayRequest): Flow<GatewayEvent> = flow {
        val events: Iterator<StreamItem>
        val closer: AutoCloseable
        try {
            if (usesServerFallback(req.model)) {
                val response = client.beta().messages().createStreaming(betaParams(req))
                closer = response
                events = response.stream().iterator().asSequence().map(::betaItem).iterator()
            } else {
                val response = client.messages().createStreaming(params(req))
                closer = response
                events = response.stream().iterator().asSequence().map(::item).iterator()
            }
        } catch (e: Throwable) {
            throw mapError(e)
        }
        closer.use {
            val usage = UsageAccumulator()
            var stopReason: String? = null
            while (true) {
                // Only the SDK calls are wrapped; exceptions from downstream collectors pass through untouched.
                val next = try {
                    if (!events.hasNext()) break
                    events.next()
                } catch (e: Throwable) {
                    throw mapError(e)
                }
                when (next) {
                    is StreamItem.Text -> if (next.text.isNotEmpty()) emit(GatewayEvent.TextDelta(next.text))
                    is StreamItem.Usage -> {
                        usage.add(next)
                        emit(GatewayEvent.Usage(usage.total()))
                    }
                    is StreamItem.Stop -> {
                        stopReason = next.reason ?: stopReason
                        usage.add(next.usage)
                    }
                    StreamItem.Ignored -> Unit
                }
            }
            emit(GatewayEvent.Done(stopReason ?: throw GatewayException(GatewayErrorCode.OTHER), usage.total()))
        }
    }.flowOn(Dispatchers.IO)

    private sealed interface StreamItem {
        data class Text(val text: String) : StreamItem
        data class Usage(val input: Long?, val output: Long?, val cacheRead: Long?, val cacheWrite: Long?) : StreamItem
        data class Stop(val reason: String?, val usage: Usage) : StreamItem
        data object Ignored : StreamItem
    }

    /** message_start carries input and cache usage; message_delta carries the cumulative output and the stop reason. */
    private class UsageAccumulator {
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L

        fun add(u: StreamItem.Usage) {
            u.input?.let { input = maxOf(input, it) }
            u.output?.let { output = maxOf(output, it) }
            u.cacheRead?.let { cacheRead = maxOf(cacheRead, it) }
            u.cacheWrite?.let { cacheWrite = maxOf(cacheWrite, it) }
        }

        fun total() = TokenUsage(input, output, cacheRead, cacheWrite)
    }

    private fun item(e: RawMessageStreamEvent): StreamItem {
        e.contentBlockDelta().orElse(null)?.let { d ->
            // Thinking and signature deltas are dropped: text only.
            return d.delta().text().map<StreamItem> { StreamItem.Text(it.text()) }.orElse(StreamItem.Ignored)
        }
        e.messageStart().orElse(null)?.let { s ->
            val u = s.message().usage()
            return StreamItem.Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(null), u.cacheCreationInputTokens().orElse(null))
        }
        e.messageDelta().orElse(null)?.let { m ->
            val u = m.usage()
            return StreamItem.Stop(
                m.delta().stopReason().map { it.asString() }.orElse(null),
                StreamItem.Usage(u.inputTokens().orElse(null), u.outputTokens(), u.cacheReadInputTokens().orElse(null), u.cacheCreationInputTokens().orElse(null)),
            )
        }
        return StreamItem.Ignored
    }

    private fun betaItem(e: BetaRawMessageStreamEvent): StreamItem {
        e.contentBlockDelta().orElse(null)?.let { d ->
            return d.delta().text().map<StreamItem> { StreamItem.Text(it.text()) }.orElse(StreamItem.Ignored)
        }
        e.messageStart().orElse(null)?.let { s ->
            val u = s.message().usage()
            return StreamItem.Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(null), u.cacheCreationInputTokens().orElse(null))
        }
        e.messageDelta().orElse(null)?.let { m ->
            val u = m.usage()
            return StreamItem.Stop(
                m.delta().stopReason().map { it.asString() }.orElse(null),
                StreamItem.Usage(u.inputTokens().orElse(null), u.outputTokens(), u.cacheReadInputTokens().orElse(null), u.cacheCreationInputTokens().orElse(null)),
            )
        }
        return StreamItem.Ignored
    }

    companion object {
        /** Haiku 5.5 has no server-side fallback (TRD §4.4.2); every larger model gets it. */
        fun usesServerFallback(model: String): Boolean = !Models.isHaiku(model)

        fun params(req: GatewayRequest): MessageCreateParams {
            val output = OutputConfig.builder().effort(OutputConfig.Effort.LOW)
            req.jsonSchema?.let { schema ->
                output.format(JsonOutputFormat.builder().schema(schemaOf(schema)).build())
            }
            return MessageCreateParams.builder()
                .model(AnthropicModel.of(req.model))
                .maxTokens(req.maxTokens)
                .systemOfTextBlockParams(
                    listOf(TextBlockParam.builder().text(req.system).cacheControl(CacheControlEphemeral.builder().build()).build()),
                )
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(output.build())
                .addUserMessage(req.user)
                .build()
        }

        fun betaParams(req: GatewayRequest): com.anthropic.models.beta.messages.MessageCreateParams {
            val output = BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW)
            req.jsonSchema?.let { schema ->
                output.format(BetaJsonOutputFormat.builder().schema(betaSchemaOf(schema)).build())
            }
            return com.anthropic.models.beta.messages.MessageCreateParams.builder()
                .model(req.model)
                .maxTokens(req.maxTokens)
                .systemOfBetaTextBlockParams(
                    listOf(BetaTextBlockParam.builder().text(req.system).cacheControl(BetaCacheControlEphemeral.builder().build()).build()),
                )
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .outputConfig(output.build())
                .fallbacksDefault()
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .addUserMessage(req.user)
                .build()
        }

        fun codeFor(status: Int): GatewayErrorCode = when (status) {
            429 -> GatewayErrorCode.RATE_LIMITED
            401, 403 -> GatewayErrorCode.AUTH
            400, 404, 409, 413, 422 -> GatewayErrorCode.BAD_REQUEST
            in 500..599 -> GatewayErrorCode.OVERLOADED
            else -> GatewayErrorCode.OTHER
        }

        /** Drops the upstream message and cause; keeps only a code. */
        fun mapError(e: Throwable): Throwable = when (e) {
            is CancellationException -> e
            is GatewayException -> e
            is AnthropicServiceException -> GatewayException(codeFor(e.statusCode()))
            else -> GatewayException(GatewayErrorCode.OTHER)
        }

        private fun schemaProps(schema: String): Map<String, JsonValue> {
            val obj = Json.parseToJsonElement(schema) as? JsonObject ?: throw IllegalArgumentException("schema must be an object")
            return obj.mapValues { (_, v) -> JsonValue.from(plain(v)) }
        }

        private fun schemaOf(schema: String): JsonOutputFormat.Schema =
            JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schemaProps(schema)).build()

        private fun betaSchemaOf(schema: String): BetaJsonOutputFormat.Schema =
            BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(schemaProps(schema)).build()

        private fun plain(e: JsonElement): Any? = when (e) {
            JsonNull -> null
            is JsonObject -> e.mapValues { plain(it.value) }
            is JsonArray -> e.map { plain(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.doubleOrNull
            }
        }
    }
}
