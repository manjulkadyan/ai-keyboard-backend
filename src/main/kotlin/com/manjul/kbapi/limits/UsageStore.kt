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
 *
 * Every model call made under a [reqId] is recorded in the day's request-id list (one entry per call).
 * A [reqId] with fewer than [MAX_ATTEMPTS_PER_REQ_ID] entries is a [Reservation.Duplicate]: a retry,
 * not counted again. Once its attempts are used up, the next call is counted as a new action (or is
 * [Reservation.Exhausted]), so replaying one id cannot buy unlimited model calls.
 */
interface UsageStore {
    suspend fun reserve(uid: String, day: String, month: String, reqId: String, dailyLimit: Int, monthlyLimit: Int?): Reservation

    /** Undoes a reservation. A [reqId] not recorded for [day] is a no-op. */
    suspend fun refund(uid: String, day: String, month: String, reqId: String)

    suspend fun used(uid: String, day: String): Int

    suspend fun usedMonth(uid: String, month: String): Int
}

/** How many request-id entries a day record keeps for idempotency (oldest dropped first). */
const val MAX_REQ_IDS = 200

/** Model calls one counted action buys under the same `client_req_id`: the first call plus retries. */
const val MAX_ATTEMPTS_PER_REQ_ID = 3

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

    /**
     * The decision shared by every store implementation. [attempts] is how many calls the day record
     * already holds for the request id (0 when it is new).
     */
    fun decide(
        day: String,
        month: String,
        dayCount: Int,
        monthCount: Int,
        attempts: Int,
        dailyLimit: Int,
        monthlyLimit: Int?,
    ): Reservation {
        fun left(d: Int, m: Int) = minOf(dailyLimit - d, monthlyLimit?.let { it - m } ?: Int.MAX_VALUE).coerceAtLeast(0)
        if (attempts in 1 until MAX_ATTEMPTS_PER_REQ_ID) return Reservation.Duplicate(left(dayCount, monthCount))
        val dayFull = dayCount >= dailyLimit
        val monthFull = monthlyLimit != null && monthCount >= monthlyLimit
        return when {
            monthFull -> Reservation.Exhausted(monthReset(month))
            dayFull -> Reservation.Exhausted(dayReset(day))
            else -> Reservation.Ok(left(dayCount + 1, monthCount + 1))
        }
    }
}

/**
 * The request-id list after a reservation: a duplicate adds one attempt; a new count replaces the id's
 * old attempts with one entry. Keeps the [MAX_REQ_IDS] most recent entries.
 */
internal fun reqIdsAfter(reqIds: List<String>, reqId: String, r: Reservation): List<String> = when (r) {
    is Reservation.Duplicate -> reqIds + reqId
    is Reservation.Ok -> reqIds.filter { it != reqId } + reqId
    is Reservation.Exhausted -> reqIds
}.takeLast(MAX_REQ_IDS)
