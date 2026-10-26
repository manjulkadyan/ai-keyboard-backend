package com.manjul.kbapi.limits

import java.time.Clock
import java.time.Duration
import kotlin.math.ceil

data class RateDecision(val allowed: Boolean, val retryAfterSeconds: Long)

/**
 * Token bucket per key: capacity [perMinute], refilled continuously at [perMinute]/60 per second.
 * Keys idle for 10 minutes are evicted. Keys (uids, IPs) are never logged.
 */
class RateLimiter(private val perMinute: Int, private val clock: Clock) {
    private class Bucket(var tokens: Double, var updatedMs: Long)

    private val buckets = HashMap<String, Bucket>()
    private var lastSweepMs = Long.MIN_VALUE
    private val refillPerMs = perMinute / 60_000.0

    @Synchronized
    fun tryAcquire(key: String): RateDecision {
        val now = clock.millis()
        sweep(now)
        val b = buckets.getOrPut(key) { Bucket(perMinute.toDouble(), now) }
        b.tokens = minOf(perMinute.toDouble(), b.tokens + (now - b.updatedMs).coerceAtLeast(0) * refillPerMs)
        b.updatedMs = now
        if (b.tokens >= 1.0) {
            b.tokens -= 1.0
            return RateDecision(true, 0)
        }
        val waitMs = (1.0 - b.tokens) / refillPerMs
        return RateDecision(false, ceil(waitMs / 1000.0).toLong().coerceAtLeast(1))
    }

    @Synchronized
    fun trackedKeys(): Int = buckets.size

    private fun sweep(now: Long) {
        if (lastSweepMs != Long.MIN_VALUE && now - lastSweepMs < SWEEP_EVERY_MS) return
        lastSweepMs = now
        buckets.entries.removeIf { now - it.value.updatedMs >= IDLE_MS }
    }

    companion object {
        private val IDLE_MS = Duration.ofMinutes(10).toMillis()
        private val SWEEP_EVERY_MS = Duration.ofMinutes(1).toMillis()
    }
}
