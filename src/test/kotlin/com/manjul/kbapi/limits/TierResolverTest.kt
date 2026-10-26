package com.manjul.kbapi.limits

import com.manjul.kbapi.testing.MutableClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class TierResolverTest {
    private val clock = MutableClock()
    private val entitlements = InMemoryEntitlementStore()
    private val integrity = InMemoryIntegrityVerdictCache()
    private val resolver = TierResolver(entitlements, integrity, clock)

    @Test
    fun `entitlement plus fresh verdict is pro`() = runBlocking {
        entitlements.put("u", Entitlement(active = true, expiresAt = null))
        integrity.put("u", IntegrityVerdict(ok = true, checkedAt = clock.instant().minus(Duration.ofHours(23))))
        assertEquals(Tier.PRO, resolver.tier("u"))
    }

    @Test
    fun `entitlement plus a 25 h verdict is free`() = runBlocking {
        entitlements.put("u", Entitlement(active = true, expiresAt = null))
        integrity.put("u", IntegrityVerdict(ok = true, checkedAt = clock.instant().minus(Duration.ofHours(25))))
        assertEquals(Tier.FREE, resolver.tier("u"))
    }

    @Test
    fun `no entitlement is free`() = runBlocking {
        integrity.put("u", IntegrityVerdict(ok = true, checkedAt = clock.instant()))
        assertEquals(Tier.FREE, resolver.tier("u"))
    }

    @Test
    fun `expired entitlement or failed verdict is free`() = runBlocking {
        entitlements.put("u", Entitlement(active = true, expiresAt = clock.instant().minusSeconds(1)))
        integrity.put("u", IntegrityVerdict(ok = true, checkedAt = clock.instant()))
        assertEquals(Tier.FREE, resolver.tier("u"))
        entitlements.put("v", Entitlement(active = true, expiresAt = null))
        integrity.put("v", IntegrityVerdict(ok = false, checkedAt = clock.instant()))
        assertEquals(Tier.FREE, resolver.tier("v"))
    }
}
