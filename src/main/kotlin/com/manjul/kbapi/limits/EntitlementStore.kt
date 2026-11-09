package com.manjul.kbapi.limits

import com.manjul.kbapi.billing.EntitlementRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The Pro verdict [TierResolver] reads. Derived from the uid's [EntitlementRecord]. */
data class Entitlement(val active: Boolean, val expiresAt: Instant?)

/**
 * Purchase token to uid links (decision 3): one record per token and at most one per uid. A verify from
 * another uid moves the token. Every implementation keeps [link] atomic, so two concurrent verifies of the
 * same token leave one owner.
 */
interface EntitlementStore {
    suspend fun entitlement(uid: String): Entitlement?

    /** Stores [record]. Removes any record with the same token (another uid) and the uid's previous token. */
    suspend fun link(record: EntitlementRecord)

    suspend fun byToken(token: String): EntitlementRecord?

    suspend fun byUid(uid: String): EntitlementRecord?

    /** Removes the record for [token], if any (a `linkedPurchaseToken` that was replaced). */
    suspend fun deleteToken(token: String)

    /** Removes the uid's record (account deletion). Idempotent. */
    suspend fun deleteUid(uid: String)
}

/** The verdict for a record, re-evaluated at [now]. */
internal fun EntitlementRecord.toEntitlement(now: Long): Entitlement =
    at(now).let { Entitlement(it.proActive, Instant.ofEpochMilli(it.expiresAtMs)) }

/** Single-instance store for tests and local runs. One [Mutex] serializes writes. */
class InMemoryEntitlementStore(private val clock: Clock = Clock.systemUTC()) : EntitlementStore {
    private val overrides = ConcurrentHashMap<String, Entitlement>()
    private val byToken = ConcurrentHashMap<String, EntitlementRecord>()
    private val tokenOfUid = ConcurrentHashMap<String, String>()
    private val lock = Mutex()

    /** Test hook: sets the verdict for [uid] directly, without a purchase record. */
    fun put(uid: String, e: Entitlement) {
        overrides[uid] = e
    }

    override suspend fun entitlement(uid: String): Entitlement? =
        overrides[uid] ?: byUid(uid)?.toEntitlement(clock.millis())

    override suspend fun link(record: EntitlementRecord) = lock.withLock {
        byToken.remove(record.token)?.let { old -> tokenOfUid.remove(old.uid, record.token) }
        tokenOfUid.put(record.uid, record.token)?.let { oldToken -> byToken.remove(oldToken) }
        byToken[record.token] = record
    }

    override suspend fun byToken(token: String): EntitlementRecord? = byToken[token]

    override suspend fun byUid(uid: String): EntitlementRecord? = tokenOfUid[uid]?.let { byToken[it] }

    override suspend fun deleteToken(token: String) = lock.withLock {
        byToken.remove(token)?.let { tokenOfUid.remove(it.uid, token) }
        Unit
    }

    override suspend fun deleteUid(uid: String) = lock.withLock {
        overrides.remove(uid)
        tokenOfUid.remove(uid)?.let { byToken.remove(it) }
        Unit
    }
}
