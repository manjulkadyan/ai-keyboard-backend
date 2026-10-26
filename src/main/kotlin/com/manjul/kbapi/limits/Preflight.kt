package com.manjul.kbapi.limits

import com.google.cloud.firestore.Firestore
import com.manjul.kbapi.ApiException
import com.manjul.kbapi.Config
import com.manjul.kbapi.Errors
import com.manjul.kbapi.auth.Principal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.time.Clock

/** A request that passed preflight. Pass [reservedDay] to [QuotaService.refund]. */
data class Admission(val reservedDay: String, val leftToday: Int, val duplicate: Boolean)

/**
 * Request preflight for AI actions, in this order:
 * kill switch -> spend pause (503 `ai_paused`) -> uid and IP rate limits (429) -> input length (400 `too_long`)
 * -> quota reserve (402 `quota`). Failures throw [ApiException]; nothing request-derived is attached.
 */
class Preflight(
    private val config: Config,
    private val spend: SpendTracker,
    private val uidLimiter: RateLimiter,
    private val ipLimiter: RateLimiter,
    private val quota: QuotaService,
) {
    suspend fun admit(p: Principal, clientIp: String, inputChars: Int, reqId: String): Admission {
        if (!config.aiEnabled) throw ApiException(Errors.aiPaused())
        if (spend.isPaused()) throw ApiException(Errors.aiPaused())
        val byUid = uidLimiter.tryAcquire(p.uid)
        if (!byUid.allowed) throw ApiException(Errors.rateLimited(byUid.retryAfterSeconds))
        val byIp = ipLimiter.tryAcquire(clientIp)
        if (!byIp.allowed) throw ApiException(Errors.rateLimited(byIp.retryAfterSeconds))
        if (inputChars > config.maxInputChars) throw ApiException(Errors.tooLong())
        val day = quota.today()
        return when (val r = quota.reserve(p, reqId, day)) {
            is Reservation.Ok -> Admission(day, r.leftToday, duplicate = false)
            is Reservation.Duplicate -> Admission(day, r.leftToday, duplicate = true)
            is Reservation.Exhausted -> throw ApiException(Errors.quota(r.resetsAt))
        }
    }
}

/**
 * The client IP for the per-IP bucket. Behind Cloud Run's front end the connecting peer is the proxy,
 * so the last `X-Forwarded-For` entry (the one the front end appended) is used. It is never logged.
 */
fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
        ?: request.origin.remoteHost

/** The quota, rate-limit and spend wiring for one process. */
class Limits(
    val quota: QuotaService,
    val uidLimiter: RateLimiter,
    val ipLimiter: RateLimiter,
    val spend: SpendTracker,
    val preflight: Preflight,
) {
    companion object {
        fun create(
            config: Config,
            clock: Clock,
            usage: UsageStore,
            spendStore: SpendStore,
            entitlements: EntitlementStore = InMemoryEntitlementStore(),
            integrity: IntegrityVerdictCache = InMemoryIntegrityVerdictCache(),
        ): Limits {
            val quota = QuotaService(usage, TierResolver(entitlements, integrity, clock), config, clock)
            val uid = RateLimiter(config.uidPerMinute, clock)
            val ip = RateLimiter(config.ipPerMinute, clock)
            val spend = SpendTracker(spendStore, ModelPrice.DEFAULTS, config.hourlySpendLimitUsd, clock)
            return Limits(quota, uid, ip, spend, Preflight(config, spend, uid, ip, quota))
        }

        fun inMemory(config: Config, clock: Clock): Limits =
            create(config, clock, InMemoryUsageStore(), InMemorySpendStore())

        /** Entitlement and integrity stay empty in-memory stores until S4 writes them. */
        fun firestore(config: Config, clock: Clock, db: Firestore): Limits =
            create(config, clock, FirestoreUsageStore(db), FirestoreSpendStore(db))
    }
}
