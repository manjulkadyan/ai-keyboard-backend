package com.manjul.kbapi.limits

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A Pro entitlement record. Written by S4's `/v1/entitlement/verify`; empty until then. */
data class Entitlement(val active: Boolean, val expiresAt: Instant?)

interface EntitlementStore {
    suspend fun entitlement(uid: String): Entitlement?
}

class InMemoryEntitlementStore : EntitlementStore {
    private val map = ConcurrentHashMap<String, Entitlement>()
    fun put(uid: String, e: Entitlement) {
        map[uid] = e
    }
    override suspend fun entitlement(uid: String): Entitlement? = map[uid]
}
