package com.manjul.kbapi.account

import com.manjul.kbapi.billing.EntitlementRecord
import com.manjul.kbapi.billing.SubState
import com.manjul.kbapi.limits.InMemoryEntitlementStore
import com.manjul.kbapi.limits.InMemoryIntegrityVerdictCache
import com.manjul.kbapi.limits.InMemoryUsageStore
import com.manjul.kbapi.limits.RateLimiter
import com.manjul.kbapi.report.AiReport
import com.manjul.kbapi.report.InMemoryReportStore
import com.manjul.kbapi.testing.FakeUserDeleter
import com.manjul.kbapi.testing.TestKeys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountDeletionServiceTest {
    private val usage = InMemoryUsageStore()
    private val entitlements = InMemoryEntitlementStore()
    private val integrity = InMemoryIntegrityVerdictCache()
    private val reports = InMemoryReportStore()
    private val limiter = RateLimiter(1, TestKeys.clock)
    private val deleter = FakeUserDeleter()
    private val service = AccountDeletionService(
        usage, entitlements, integrity, reports, deleter, rateLimiters = listOf(limiter),
    )

    private fun seed(uid: String) = runBlocking {
        usage.reserve(uid, "20261026", "202610", "r1", 10, null)
        entitlements.link(EntitlementRecord(uid, "tok-$uid", "pro", SubState.ACTIVE, true, 1L, 1L))
        integrity.record(uid, TestKeys.NOW.toEpochMilli())
        reports.save(AiReport(uid, "c1", "rewrite@1", "wrong", null, TestKeys.NOW, TestKeys.NOW))
        limiter.tryAcquire(uid)
    }

    @Test
    fun `every store is empty for the uid afterwards and Firebase is called once`() = runBlocking {
        seed("uid-a")
        seed("uid-b")
        assertEquals(DeleteResult(deleted = true), service.delete("uid-a", "del-1"))

        assertEquals(0, usage.used("uid-a", "20261026"))
        assertEquals(0, usage.usedMonth("uid-a", "202610"))
        assertNull(entitlements.byUid("uid-a"))
        assertNull(entitlements.byToken("tok-uid-a"))
        assertNull(integrity.verdict("uid-a"))
        assertTrue(reports.all().none { it.uid == "uid-a" })
        assertTrue(limiter.tryAcquire("uid-a").allowed, "the rate bucket was forgotten")
        assertEquals(listOf("uid-a"), deleter.deleted)

        // Nobody else is touched.
        assertEquals(1, usage.used("uid-b", "20261026"))
        assertNotNull(entitlements.byUid("uid-b"))
        assertNotNull(integrity.verdict("uid-b"))
        assertEquals(1, reports.all().count { it.uid == "uid-b" })
    }

    @Test
    fun `a second call still returns deleted`() = runBlocking {
        seed("uid-a")
        service.delete("uid-a", "del-1")
        assertEquals(DeleteResult(deleted = true), service.delete("uid-a", "del-1"))
        assertEquals(DeleteResult(deleted = true), service.delete("never-seen", "del-2"))
    }

    @Test
    fun `without a Firebase deleter (dev auth) the stores are still wiped`() = runBlocking {
        seed("uid-a")
        val dev = AccountDeletionService(usage, entitlements, integrity, reports, auth = null)
        assertEquals(DeleteResult(deleted = true), dev.delete("uid-a", "del-1"))
        assertNull(entitlements.byUid("uid-a"))
        assertTrue(deleter.deleted.isEmpty())
    }
}
