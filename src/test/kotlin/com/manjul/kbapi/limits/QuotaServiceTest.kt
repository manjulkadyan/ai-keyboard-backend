package com.manjul.kbapi.limits

import com.manjul.kbapi.auth.Principal
import com.manjul.kbapi.testing.MutableClock
import com.manjul.kbapi.testing.testConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuotaServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-26T10:00:00Z"))
    private val store = InMemoryUsageStore()
    private val entitlements = InMemoryEntitlementStore()
    private val integrity = InMemoryIntegrityVerdictCache()
    private val quota = QuotaService(store, TierResolver(entitlements, integrity, clock), testConfig(), clock)
    private val p = Principal.of("uid-1")

    private fun makePro() {
        entitlements.put(p.uid, Entitlement(active = true, expiresAt = null))
        integrity.put(p.uid, IntegrityVerdict(ok = true, checkedAt = clock.instant()))
    }

    @Test
    fun `ten free reserves succeed and the eleventh is exhausted until next utc midnight`() = runBlocking {
        repeat(10) { i ->
            val r = quota.reserve(p, "req-$i")
            assertEquals(Reservation.Ok(9 - i), r)
        }
        val r = quota.reserve(p, "req-10")
        assertIs<Reservation.Exhausted>(r)
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), r.resetsAt)
    }

    @Test
    fun `same reqId twice is duplicate and counts once`() = runBlocking {
        assertEquals(Reservation.Ok(9), quota.reserve(p, "same"))
        assertEquals(Reservation.Duplicate(9), quota.reserve(p, "same"))
        assertEquals(1, store.used(p.uid, "20261026"))
    }

    @Test
    fun `refund restores the count and an unknown reqId is a no-op`() = runBlocking {
        quota.reserve(p, "a")
        quota.reserve(p, "b")
        quota.refund(p, "a", "20261026")
        assertEquals(1, store.used(p.uid, "20261026"))
        quota.refund(p, "unknown", "20261026")
        quota.refund(p, "a", "20261026")
        assertEquals(1, store.used(p.uid, "20261026"))
        assertEquals(9, quota.status(p).aiLeftToday)
    }

    @Test
    fun `twenty concurrent reserves at nine give exactly one ok`() = runBlocking {
        repeat(9) { quota.reserve(p, "pre-$it") }
        val results = (0 until 20).map { i -> async(Dispatchers.Default) { quota.reserve(p, "c-$i") } }.awaitAll()
        assertEquals(1, results.count { it is Reservation.Ok })
        assertEquals(19, results.count { it is Reservation.Exhausted })
        assertEquals(10, store.used(p.uid, "20261026"))
    }

    @Test
    fun `pro gets 150 a day`() = runBlocking {
        makePro()
        repeat(150) { assertIs<Reservation.Ok>(quota.reserve(p, "r-$it")) }
        assertIs<Reservation.Exhausted>(quota.reserve(p, "r-150"))
        assertEquals(Tier.PRO, quota.status(p).tier)
    }

    @Test
    fun `pro 3001st action in a month is exhausted even with a low day count`() = runBlocking {
        clock.now = Instant.parse("2026-10-01T08:00:00Z")
        makePro()
        var n = 0
        // 20 days x 150 = 3,000 actions, all within October.
        repeat(20) {
            integrity.put(p.uid, IntegrityVerdict(ok = true, checkedAt = clock.instant()))
            repeat(150) { assertIs<Reservation.Ok>(quota.reserve(p, "m-${n++}")) }
            clock.advance(Duration.ofDays(1))
        }
        integrity.put(p.uid, IntegrityVerdict(ok = true, checkedAt = clock.instant()))
        val r = quota.reserve(p, "m-${n++}")
        assertIs<Reservation.Exhausted>(r)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), r.resetsAt)
        assertEquals(0, quota.status(p).aiLeftToday)
    }

    @Test
    fun `refund across midnight refunds the day the reservation was made`() = runBlocking {
        clock.now = Instant.parse("2026-10-26T23:59:59Z")
        val day = quota.today()
        assertEquals("20261026", day)
        quota.reserve(p, "late", day)
        clock.now = Instant.parse("2026-10-27T00:00:01Z")
        quota.refund(p, "late", day)
        assertEquals(0, store.used(p.uid, "20261026"))
        assertEquals(0, store.used(p.uid, "20261027"))
        assertEquals(0, store.usedMonth(p.uid, "202610"))
    }

    @Test
    fun `status reports the free allowance`() = runBlocking {
        quota.reserve(p, "x")
        val s = quota.status(p)
        assertEquals(QuotaStatus(9, Instant.parse("2026-10-27T00:00:00Z"), Tier.FREE), s)
    }
}
