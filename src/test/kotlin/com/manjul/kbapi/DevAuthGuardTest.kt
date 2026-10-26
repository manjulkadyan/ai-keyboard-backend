package com.manjul.kbapi

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevAuthGuardTest {

    @Test
    fun `dev auth on cloud run refuses to start`() {
        assertThrows<IllegalStateException> {
            Config.fromEnv(mapOf("AUTH_MODE" to "dev", "K_SERVICE" to "kb-api"))
        }
    }

    @Test
    fun `dev auth locally is allowed`() {
        val config = Config.fromEnv(mapOf("AUTH_MODE" to "dev"))
        assertEquals(AuthMode.DEV, config.authMode)
    }

    @Test
    fun `firebase mode is the default and needs project ids`() {
        assertThrows<IllegalStateException> { Config.fromEnv(emptyMap()) }
        val config = Config.fromEnv(
            mapOf("FIREBASE_PROJECT_ID" to "p", "FIREBASE_PROJECT_NUMBER" to "1", "K_SERVICE" to "kb-api"),
        )
        assertEquals(AuthMode.FIREBASE, config.authMode)
        assertEquals("claude-haiku-5-5", config.rewriteModel)
        assertEquals(4000, config.maxInputChars)
        assertTrue(config.aiEnabled)
    }

    @Test
    fun `unknown auth mode is rejected`() {
        assertThrows<IllegalStateException> { Config.fromEnv(mapOf("AUTH_MODE" to "none")) }
    }

    @Test
    fun `overrides are read and the api key is optional`() {
        val config = Config.fromEnv(
            mapOf("AUTH_MODE" to "dev", "AI_ENABLED" to "false", "FREE_DAILY" to "3", "ANTHROPIC_API_KEY" to ""),
        )
        assertFalse(config.aiEnabled)
        assertEquals(3, config.freeDaily)
        assertEquals(null, config.anthropicApiKey)
    }

    @Test
    fun `config toString never prints the api key`() {
        val config = Config.fromEnv(mapOf("AUTH_MODE" to "dev", "ANTHROPIC_API_KEY" to "not-a-real-key-value"))
        assertFalse(config.toString().contains("not-a-real-key-value"))
    }
}
