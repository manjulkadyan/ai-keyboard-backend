package com.manjul.kbapi.limits

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed interface Reservation {
    data class Ok(val leftToday: Int) : Reservation
    data class Duplicate(val leftToday: Int) : Reservation
    data class Exhausted(val resetsAt: Instant) : Reservation
}

/**
 * Per-uid AI action counters. [day] is `yyyyMMdd` and [month] is `yyyyMM`, both UTC, fixed by the
 * caller at reserve time so a refund after midnight hits the day the action counted on.
 * A [reqId] already recorded for the day is a [Reservation.Duplicate] and is not counted again.
 */
interface UsageStore {
    suspend fun reserve(uid: String, day: String, month: String, reqId: String, dailyLimit: Int, monthlyLimit: Int?): Reservation

    /** Undoes a reservation. A [reqId] not recorded for [day] is a no-op. */
    suspend fun refund(uid: String, day: String, month: String, reqId: String)

    suspend fun used(uid: String, day: String): Int

    suspend fun usedMonth(uid: String, month: String): Int
}

/** How many request ids a day record keeps for idempotency (most recent first out). */
const val MAX_REQ_IDS = 200

object UsageKeys {
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

    fun day(now: Instant): String = DAY.format(now.atZone(ZoneOffset.UTC))
    fun month(now: Instant): String = MONTH.format(now.atZone(ZoneOffset.UTC))
    fun monthOfDay(day: String): String = day.take(6)

    /** UTC midnight after [day]. */
    fun dayReset(day: String): Instant =
        LocalDate.parse(day, DAY).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    /** UTC midnight on the first of the month after [month]. */
    fun monthReset(month: String): Instant =
        YearMonth.parse(month, MONTH).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    /** The decision shared by every store implementation. */
    fun decide(
        day: String,
        month: String,
        dayCount: Int,
        monthCount: Int,
        alreadyCounted: Boolean,
        dailyLimit: Int,
        monthlyLimit: Int?,
    ): Reservation {
        fun left(d: Int, m: Int) = minOf(dailyLimit - d, monthlyLimit?.let { it - m } ?: Int.MAX_VALUE).coerceAtLeast(0)
        if (alreadyCounted) return Reservation.Duplicate(left(dayCount, monthCount))
        val dayFull = dayCount >= dailyLimit
        val monthFull = monthlyLimit != null && monthCount >= monthlyLimit
        return when {
            monthFull -> Reservation.Exhausted(monthReset(month))
            dayFull -> Reservation.Exhausted(dayReset(day))
            else -> Reservation.Ok(left(dayCount + 1, monthCount + 1))
        }
    }
}
