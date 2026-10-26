package com.manjul.kbapi.limits

import java.time.Clock
import java.time.Duration

/** PRO only when the entitlement is active **and** a passing integrity verdict is at most 24 h old (decision 8). */
class TierResolver(
    private val entitlements: EntitlementStore,
    private val integrity: IntegrityVerdictCache,
    private val clock: Clock,
) {
    suspend fun tier(uid: String): Tier {
        val now = clock.instant()
        val e = entitlements.entitlement(uid) ?: return Tier.FREE
        if (!e.active || (e.expiresAt != null && !e.expiresAt.isAfter(now))) return Tier.FREE
        val v = integrity.verdict(uid) ?: return Tier.FREE
        if (!v.ok || v.checkedAt.isAfter(now) || Duration.between(v.checkedAt, now) > MAX_VERDICT_AGE) return Tier.FREE
        return Tier.PRO
    }

    companion object {
        val MAX_VERDICT_AGE: Duration = Duration.ofHours(24)
    }
}
