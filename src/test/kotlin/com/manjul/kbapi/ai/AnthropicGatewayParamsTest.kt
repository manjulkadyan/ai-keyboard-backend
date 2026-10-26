package com.manjul.kbapi.ai

import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.messages.OutputConfig
import com.manjul.kbapi.routes.PolishRequest
import com.manjul.kbapi.routes.RewriteRequest
import com.manjul.kbapi.testing.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicGatewayParamsTest {
    private val prompts = PromptRegistry()
    private val config = testConfig().copy(rewriteModel = "claude-haiku-5-5")
    private val rewrite = RewriteRequest(mode = "fix", locale = "en", text = "helo", clientReqId = "r1")

    @Test
    fun `rewrite params carry model, max tokens, cached system, adaptive thinking and low effort`() {
        val req = RewriteService.singleRequest(config, prompts, rewrite)
        val params = AnthropicGateway.params(req)
        assertEquals(1024L, params.maxTokens())
        assertEquals(config.rewriteModel, params.model().asString())
        val blocks = params.system().get().asTextBlockParams()
        assertEquals(prompts.get("rewrite").system, blocks.last().text())
        assertTrue(blocks.last().cacheControl().isPresent)
        assertTrue(params.thinking().get().isAdaptive())
        assertEquals(OutputConfig.Effort.LOW, params.outputConfig().get().effort().get())
        assertFalse(params.outputConfig().get().format().isPresent)
        assertEquals(1, params.messages().size)
        assertEquals(req.user, params.messages().single().content().asString())
        assertFalse(AnthropicGateway.usesServerFallback(req.model))
    }

    @Test
    fun `json schema sets the output format`() {
        val req = PolishService.request(config, prompts, PolishRequest(transcript = "um hi", targetLang = "es", clientReqId = "p1"))
        val params = AnthropicGateway.params(req)
        assertEquals(2048L, params.maxTokens())
        val format = params.outputConfig().get().format().get()
        val schema = format.schema()._additionalProperties()
        assertEquals("object", schema["type"]!!.asString().get())
        assertTrue(schema.containsKey("properties"))
        assertEquals(OutputConfig.Effort.LOW, params.outputConfig().get().effort().get())
    }

    @Test
    fun `sonnet uses the beta API with server-side fallback`() {
        val req = RewriteService.singleRequest(config.copy(rewriteModel = "claude-sonnet-5-5"), prompts, rewrite)
        assertTrue(AnthropicGateway.usesServerFallback(req.model))
        val params = AnthropicGateway.betaParams(req)
        assertEquals("claude-sonnet-5-5", params.model().asString())
        assertEquals(1024L, params.maxTokens())
        assertTrue(params.fallbacks().isPresent)
        assertTrue(params.betas().get().contains(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01))
        assertTrue(params.system().get().asBetaTextBlockParams().last().cacheControl().isPresent)
        assertTrue(params.thinking().get().isAdaptive())
        assertEquals(BetaOutputConfig.Effort.LOW, params.outputConfig().get().effort().get())
    }

    @Test
    fun `upstream status codes map to gateway codes`() {
        assertEquals(GatewayErrorCode.RATE_LIMITED, AnthropicGateway.codeFor(429))
        assertEquals(GatewayErrorCode.OVERLOADED, AnthropicGateway.codeFor(529))
        assertEquals(GatewayErrorCode.OVERLOADED, AnthropicGateway.codeFor(503))
        assertEquals(GatewayErrorCode.AUTH, AnthropicGateway.codeFor(401))
        assertEquals(GatewayErrorCode.AUTH, AnthropicGateway.codeFor(403))
        assertEquals(GatewayErrorCode.BAD_REQUEST, AnthropicGateway.codeFor(400))
        assertEquals(GatewayErrorCode.OTHER, AnthropicGateway.codeFor(0))
    }

    @Test
    fun `gateway exceptions carry no upstream message`() {
        val e = GatewayException(GatewayErrorCode.OVERLOADED)
        assertEquals("OVERLOADED", e.message)
        assertEquals(null, e.cause)
    }
}
