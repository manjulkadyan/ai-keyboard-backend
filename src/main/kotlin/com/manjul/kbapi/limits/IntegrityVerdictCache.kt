package com.manjul.kbapi.limits

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The latest Play Integrity verdict for a uid (TRD §11.4). Filled by S4; empty until then. */
data class IntegrityVerdict(val ok: Boolean, val checkedAt: Instant)

interface IntegrityVerdictCache {
    suspend fun verdict(uid: String): IntegrityVerdict?
}

class InMemoryIntegrityVerdictCache : IntegrityVerdictCache {
    private val map = ConcurrentHashMap<String, IntegrityVerdict>()
    fun put(uid: String, v: IntegrityVerdict) {
        map[uid] = v
    }
    override suspend fun verdict(uid: String): IntegrityVerdict? = map[uid]
}
