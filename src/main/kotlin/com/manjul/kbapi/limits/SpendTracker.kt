package com.manjul.kbapi.limits

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class TokenUsage(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long)

/** List prices in USD per million tokens (TRD §11.4). Cache writes are billed at 1.25x input. */
data class ModelPrice(val inputPerMTok: Double, val outputPerMTok: Double, val cacheReadPerMTok: Double) {
    fun cost(u: TokenUsage): Double =
        (u.input * inputPerMTok + u.output * outputPerMTok + u.cacheRead * cacheReadPerMTok +
            u.cacheWrite * inputPerMTok * CACHE_WRITE_MULTIPLIER) / 1_000_000.0

    companion object {
        const val CACHE_WRITE_MULTIPLIER = 1.25
        val HAIKU = ModelPrice(0.10, 0.50, 0.01)
        val SONNET = ModelPrice(2.0, 10.0, 0.20)
        val OPUS = ModelPrice(4.0, 20.0, 0.20)

        val DEFAULTS: Map<String, ModelPrice> = mapOf(
            "claude-haiku-5-5" to HAIKU,
            "claude-sonnet-5-5" to SONNET,
            "claude-opus-5-5" to OPUS,
        )

        /** Exact id, then model family, then the most expensive price so unknown models over-count. */
        fun lookup(prices: Map<String, ModelPrice>, model: String): ModelPrice =
            prices[model]
                ?: prices.entries.firstOrNull { (id, _) -> family(id) != null && family(id) == family(model) }?.value
                ?: prices.values.maxByOrNull { it.outputPerMTok }
                ?: OPUS

        private fun family(model: String): String? = listOf("haiku", "sonnet", "opus").firstOrNull { it in model }
    }
}

/** Hourly USD spend buckets keyed `yyyyMMddHH` (UTC), shared across instances. */
interface SpendStore {
    suspend fun add(hourKey: String, usd: Double)
    suspend fun get(hourKey: String): Double
}

/**
 * The spend breaker: AI is paused while the rolling hour (current plus previous hour bucket)
 * exceeds [limitUsd]. The store value is cached for 30 s; a local [record] invalidates it.
 */
class SpendTracker(
    private val store: SpendStore,
    private val prices: Map<String, ModelPrice>,
    private val limitUsd: Double,
    private val clock: Clock,
) {
    private class Cached(val usd: Double, val at: Instant)

    @Volatile private var cached: Cached? = null

    suspend fun record(model: String, usage: TokenUsage) {
        val usd = ModelPrice.lookup(prices, model).cost(usage)
        if (usd <= 0.0) return
        store.add(hourKey(clock.instant()), usd)
        cached = null
    }

    suspend fun isPaused(): Boolean = rollingHourUsd() > limitUsd

    suspend fun rollingHourUsd(): Double {
        val now = clock.instant()
        cached?.let { if (Duration.between(it.at, now) < CACHE_TTL && !now.isBefore(it.at)) return it.usd }
        val usd = store.get(hourKey(now)) + store.get(hourKey(now.minus(Duration.ofHours(1))))
        cached = Cached(usd, now)
        return usd
    }

    companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(30)
        private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)

        fun hourKey(at: Instant): String = HOUR.format(at)
    }
}
