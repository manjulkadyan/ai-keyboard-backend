package com.manjul.kbapi.limits

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The latest Play Integrity verdict for a uid (TRD §11.4, decision 8). */
data class IntegrityVerdict(val ok: Boolean, val checkedAt: Instant)

interface IntegrityVerdictCache {
    suspend fun verdict(uid: String): IntegrityVerdict?

    /**
     * Records a passing verdict at [atMs]. The auth chain calls it after a real App Check pass (the App Check
     * token carries the Play Integrity attestation). Dev auth never calls it.
     */
    suspend fun record(uid: String, atMs: Long)

    /** Removes the uid's verdict (account deletion). Idempotent. */
    suspend fun deleteUid(uid: String)
}

class InMemoryIntegrityVerdictCache : IntegrityVerdictCache {
    private val map = ConcurrentHashMap<String, IntegrityVerdict>()
    fun put(uid: String, v: IntegrityVerdict) {
        map[uid] = v
    }
    override suspend fun verdict(uid: String): IntegrityVerdict? = map[uid]

    override suspend fun record(uid: String, atMs: Long) {
        map[uid] = IntegrityVerdict(ok = true, checkedAt = Instant.ofEpochMilli(atMs))
    }

    override suspend fun deleteUid(uid: String) {
        map.remove(uid)
    }
}
