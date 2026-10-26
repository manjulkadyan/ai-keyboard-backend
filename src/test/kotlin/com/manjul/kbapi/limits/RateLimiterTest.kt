package com.manjul.kbapi.limits

import com.manjul.kbapi.testing.MutableClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private val clock = MutableClock()
    private val limiter = RateLimiter(10, clock)

    @Test
    fun `ten allowed then the eleventh is denied with a short retry`() {
        repeat(10) { assertTrue(limiter.tryAcquire("u").allowed) }
        val d = limiter.tryAcquire("u")
        assertFalse(d.allowed)
        assertTrue(d.retryAfterSeconds in 1..6, "retry ${d.retryAfterSeconds}")
    }

    @Test
    fun `refills after six seconds`() {
        repeat(10) { limiter.tryAcquire("u") }
        assertFalse(limiter.tryAcquire("u").allowed)
        clock.advance(Duration.ofSeconds(6))
        assertTrue(limiter.tryAcquire("u").allowed)
        assertFalse(limiter.tryAcquire("u").allowed)
    }

    @Test
    fun `keys are independent and idle keys are evicted`() {
        repeat(10) { limiter.tryAcquire("a") }
        assertTrue(limiter.tryAcquire("b").allowed)
        assertEquals(2, limiter.trackedKeys())
        clock.advance(Duration.ofMinutes(11))
        limiter.tryAcquire("c")
        assertEquals(1, limiter.trackedKeys())
    }
}
