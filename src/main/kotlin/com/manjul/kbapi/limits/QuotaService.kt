package com.manjul.kbapi.limits

import com.manjul.kbapi.Config
import com.manjul.kbapi.auth.Principal
import java.time.Clock
import java.time.Instant

data class QuotaStatus(val aiLeftToday: Int, val resetsAt: Instant, val tier: Tier)

/**
 * Daily and monthly AI action quota (FREE 10/day; PRO 150/day and 3,000/month), reset at UTC midnight.
 * The day is fixed at reserve time: pass the value of [today] taken before the call to [refund].
 */
class QuotaService(
    private val store: UsageStore,
    private val tiers: TierResolver,
    private val config: Config,
    private val clock: Clock,
) {
    fun today(): String = UsageKeys.day(clock.instant())

    suspend fun reserve(p: Principal, reqId: String, day: String = today()): Reservation {
        val tier = tiers.tier(p.uid)
        return store.reserve(p.uid, day, UsageKeys.monthOfDay(day), reqId, dailyLimit(tier), monthlyLimit(tier))
    }

    suspend fun refund(p: Principal, reqId: String, reservedDay: String) {
        store.refund(p.uid, reservedDay, UsageKeys.monthOfDay(reservedDay), reqId)
    }

    suspend fun status(p: Principal): QuotaStatus {
        val day = today()
        val tier = tiers.tier(p.uid)
        val dayLeft = dailyLimit(tier) - store.used(p.uid, day)
        val monthLeft = monthlyLimit(tier)?.let { it - store.usedMonth(p.uid, UsageKeys.monthOfDay(day)) } ?: Int.MAX_VALUE
        return QuotaStatus(minOf(dayLeft, monthLeft).coerceAtLeast(0), UsageKeys.dayReset(day), tier)
    }

    private fun dailyLimit(tier: Tier) = when (tier) {
        Tier.FREE -> config.freeDaily
        Tier.PRO -> config.proDaily
    }

    private fun monthlyLimit(tier: Tier): Int? = when (tier) {
        Tier.FREE -> null
        Tier.PRO -> config.proMonthly
    }
}
