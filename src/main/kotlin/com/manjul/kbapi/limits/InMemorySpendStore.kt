package com.manjul.kbapi.limits

import java.util.concurrent.ConcurrentHashMap

class InMemorySpendStore : SpendStore {
    private val buckets = ConcurrentHashMap<String, Double>()

    override suspend fun add(hourKey: String, usd: Double) {
        buckets.merge(hourKey, usd, Double::plus)
    }

    override suspend fun get(hourKey: String): Double = buckets[hourKey] ?: 0.0
}
