package com.manjul.kbapi.limits

import com.manjul.kbapi.ApiException
import com.manjul.kbapi.auth.Principal
import com.manjul.kbapi.testing.MutableClock
import com.manjul.kbapi.testing.testConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PreflightTest {
    private val clock = MutableClock()
    private val p = Principal.of("uid-1")

    private fun limits(aiEnabled: Boolean = true) = Limits.inMemory(testConfig().copy(aiEnabled = aiEnabled), clock)

    private fun code(block: suspend () -> Unit): String =
        assertFailsWith<ApiException> { runBlocking { block() } }.error.code

    @Test
    fun `kill switch comes first`() {
        val l = limits(aiEnabled = false)
        assertEquals("ai_paused", code { l.preflight.admit(p, "1.2.3.4", 99_999, "r") })
    }

    @Test
    fun `spend pause comes before rate limit`() {
        val l = limits()
        runBlocking { l.spend.record("claude-sonnet-5-5", TokenUsage(0, 2_100_000, 0, 0)) }
        repeat(20) { l.uidLimiter.tryAcquire(p.uid) }
        assertEquals("ai_paused", code { l.preflight.admit(p, "1.2.3.4", 10, "r") })
    }

    @Test
    fun `rate limit comes before length and quota`() {
        val l = limits()
        repeat(10) { runBlocking { l.preflight.admit(p, "1.2.3.4", 10, "r-$it") } }
        val e = assertFailsWith<ApiException> { runBlocking { l.preflight.admit(p, "1.2.3.4", 99_999, "r-x") } }
        assertEquals("rate_limited", e.error.code)
        assertEquals(true, e.error.headers.containsKey("Retry-After"))
    }

    @Test
    fun `ip bucket applies across uids`() {
        val l = limits()
        repeat(60) { i -> runBlocking { l.preflight.admit(Principal.of("u$i"), "9.9.9.9", 10, "r") } }
        assertEquals("rate_limited", code { l.preflight.admit(Principal.of("u-new"), "9.9.9.9", 10, "r") })
    }

    @Test
    fun `too long comes before quota and does not spend quota`() {
        val l = limits()
        assertEquals("too_long", code { l.preflight.admit(p, "1.2.3.4", 4001, "r") })
        assertEquals(10, runBlocking { l.quota.status(p).aiLeftToday })
    }

    @Test
    fun `quota exhausted is 402 and an admission names the reserved day`() = runBlocking {
        val l = limits()
        val first = l.preflight.admit(p, "1.2.3.4", 10, "r-0")
        assertEquals(Admission("20261026", 9, duplicate = false), first)
        assertEquals(Admission("20261026", 9, duplicate = true), l.preflight.admit(p, "1.2.3.4", 10, "r-0"))
        // Fill the rest of the day without tripping the rate limiter.
        repeat(9) { l.quota.reserve(p, "fill-$it") }
        clock.advance(java.time.Duration.ofMinutes(1))
        assertEquals("quota", code { l.preflight.admit(p, "1.2.3.4", 10, "r-last") })
    }
}
