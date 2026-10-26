package com.manjul.kbapi.limits

import com.manjul.kbapi.testing.MutableClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpendTrackerTest {
    private val clock = MutableClock()
    private val store = InMemorySpendStore()
    private val tracker = SpendTracker(store, ModelPrice.DEFAULTS, 20.0, clock)

    @Test
    fun `21 dollars in the hour pauses and the rolling window unpauses`() = runBlocking {
        assertFalse(tracker.isPaused())
        // Sonnet output is $10/MTok: 2.1M output tokens = $21.
        tracker.record("claude-sonnet-5-5", TokenUsage(input = 0, output = 2_100_000, cacheRead = 0, cacheWrite = 0))
        assertTrue(tracker.isPaused())
        clock.advance(Duration.ofMinutes(61))
        assertTrue(tracker.isPaused(), "previous hour still counts in the rolling hour")
        clock.advance(Duration.ofMinutes(60))
        assertFalse(tracker.isPaused())
    }

    @Test
    fun `cost uses per-MTok prices and bills cache writes at 1_25x input`() {
        val usd = ModelPrice.DEFAULTS.getValue("claude-haiku-5-5")
            .cost(TokenUsage(input = 1_000_000, output = 1_000_000, cacheRead = 1_000_000, cacheWrite = 1_000_000))
        assertEquals(0.10 + 0.50 + 0.01 + 0.125, usd, 1e-9)
    }

    @Test
    fun `unknown models are priced as the most expensive`() {
        assertEquals(ModelPrice.DEFAULTS.getValue("claude-opus-5-5"), ModelPrice.lookup(ModelPrice.DEFAULTS, "mystery"))
    }

    @Test
    fun `store value is cached for 30 seconds`() = runBlocking {
        assertFalse(tracker.isPaused())
        // Another instance spends through the shared store.
        store.add(SpendTracker.hourKey(clock.instant()), 25.0)
        assertFalse(tracker.isPaused())
        clock.advance(Duration.ofSeconds(31))
        assertTrue(tracker.isPaused())
    }
}
