package com.manjul.kbapi.limits

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Single-instance store for tests and local runs. A [Mutex] per uid makes reserve atomic. */
class InMemoryUsageStore : UsageStore {
    private class DayRecord {
        var count = 0
        val reqIds = LinkedHashSet<String>()
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val days = ConcurrentHashMap<String, DayRecord>()
    private val months = ConcurrentHashMap<String, Int>()

    private fun lock(uid: String) = locks.computeIfAbsent(uid) { Mutex() }

    override suspend fun reserve(
        uid: String,
        day: String,
        month: String,
        reqId: String,
        dailyLimit: Int,
        monthlyLimit: Int?,
    ): Reservation = lock(uid).withLock {
        val rec = days.computeIfAbsent("${uid}_$day") { DayRecord() }
        val monthCount = months["${uid}_$month"] ?: 0
        val r = UsageKeys.decide(day, month, rec.count, monthCount, reqId in rec.reqIds, dailyLimit, monthlyLimit)
        if (r is Reservation.Ok) {
            rec.count++
            rec.reqIds += reqId
            while (rec.reqIds.size > MAX_REQ_IDS) rec.reqIds.remove(rec.reqIds.first())
            months["${uid}_$month"] = monthCount + 1
        }
        r
    }

    override suspend fun refund(uid: String, day: String, month: String, reqId: String) = lock(uid).withLock {
        val rec = days["${uid}_$day"] ?: return@withLock
        if (!rec.reqIds.remove(reqId)) return@withLock
        rec.count = (rec.count - 1).coerceAtLeast(0)
        months.computeIfPresent("${uid}_$month") { _, c -> (c - 1).coerceAtLeast(0) }
        Unit
    }

    override suspend fun used(uid: String, day: String): Int = lock(uid).withLock { days["${uid}_$day"]?.count ?: 0 }

    override suspend fun usedMonth(uid: String, month: String): Int = lock(uid).withLock { months["${uid}_$month"] ?: 0 }
}
