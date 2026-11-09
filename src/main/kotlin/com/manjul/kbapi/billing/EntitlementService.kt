package com.manjul.kbapi.billing

import com.manjul.kbapi.auth.Principal
import com.manjul.kbapi.limits.EntitlementStore
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Clock

/** The `/v1/entitlement` body. Times are epoch ms. [signature] covers the decision 4 payload. */
@Serializable
data class EntitlementResponse(
    @SerialName("pro_active") val proActive: Boolean,
    @SerialName("expires_at") val expiresAt: Long,
    val signature: String,
    val state: String,
    @SerialName("issued_at") val issuedAt: Long,
)

sealed interface VerifyResult {
    data class Ok(val response: EntitlementResponse) : VerifyResult

    /** Wrong product, unknown to Play, or a pending purchase the user canceled. The client drops the token. */
    data object InvalidToken : VerifyResult

    /** Play could not be reached after retries. The client keeps the token and tries again. */
    data object Unavailable : VerifyResult
}

enum class RtdnOutcome { UPDATED, IGNORED }

/**
 * kb-api is the only Pro authority (decision 2, ARCH-006). Every verdict comes from a fresh Play read, never from
 * the client's claim or the RTDN payload: an RTDN only says "look again".
 *
 * Logs carry the uid hash and the Play status only (OPS-001). Audit lines (`kbapi.audit`) record grant and revoke
 * with the uid hash and the state, no token.
 */
class EntitlementService(
    private val api: PlayDeveloperApi,
    private val store: EntitlementStore,
    private val signer: EntitlementSigner,
    private val clock: Clock,
    private val productId: String = PRODUCT_ID,
    private val retryDelaysMs: List<Long> = listOf(200L, 800L),
) {
    suspend fun verify(uid: String, token: String, productId: String): VerifyResult {
        if (productId != this.productId) return VerifyResult.InvalidToken
        val sub = try {
            withRetry("subscription", uid) { api.subscription(token) }
        } catch (e: PlayApiException) {
            log.error("entitlement verify failed uid_hash={} play_status={}", Principal.hash(uid), e.status)
            return VerifyResult.Unavailable
        } ?: return VerifyResult.InvalidToken
        if (sub.productId != this.productId || sub.state == PlayState.PENDING_PURCHASE_CANCELED) {
            return VerifyResult.InvalidToken
        }
        if (!sub.acknowledged && sub.state.acknowledgeable()) {
            try {
                withRetry("acknowledge", uid) { api.acknowledge(sub.productId, token) }
            } catch (e: PlayApiException) {
                // Play refunds an unacknowledged purchase after 3 days; the next verify or RTDN acknowledges it.
                log.warn("entitlement acknowledge deferred uid_hash={} play_status={}", Principal.hash(uid), e.status)
            }
        }
        val record = recordOf(uid, token, sub)
        val previous = store.byUid(uid)
        store.link(record)
        sub.linkedPurchaseToken?.takeIf { it != token }?.let { store.deleteToken(it) }
        audit(uid, previous, record)
        return VerifyResult.Ok(response(record))
    }

    /** The caller's verdict from the store, re-evaluated against the clock. No Play call. */
    suspend fun current(uid: String): EntitlementResponse {
        val record = store.byUid(uid) ?: return none()
        return response(record.at(clock.millis()))
    }

    /**
     * Re-reads Play for a token named by an RTDN. A token kb-api never linked is ignored (the verify call links
     * it). [type] [RtdnType.REVOKED] and voided purchases force `revoked`; any other type takes Play's state, so a
     * late or out-of-order message cannot override a newer Play state. Throws [PlayApiException] when Play is
     * down, so Pub/Sub redelivers.
     */
    suspend fun onRtdn(token: String, type: Int): RtdnOutcome {
        val existing = store.byToken(token)
        val sub = withRetry("subscription", existing?.uid) { api.subscription(token) }
        val owner = existing ?: sub?.linkedPurchaseToken?.let { store.byToken(it) }
            ?: return RtdnOutcome.IGNORED
        val now = clock.millis()
        val record = when {
            type == RtdnType.REVOKED || type == RtdnType.VOIDED ->
                (existing ?: owner).copy(
                    token = token, state = SubState.REVOKED, proActive = false, updatedAtMs = now,
                    expiresAtMs = sub?.expiryTimeMs ?: owner.expiresAtMs,
                )
            sub == null -> owner.copy(token = token, state = SubState.EXPIRED, proActive = false, updatedAtMs = now)
            else -> recordOf(owner.uid, token, sub)
        }
        if (sub != null && !sub.acknowledged && sub.state.acknowledgeable() && sub.productId == productId) {
            try {
                withRetry("acknowledge", owner.uid) { api.acknowledge(sub.productId, token) }
            } catch (e: PlayApiException) {
                log.warn("rtdn acknowledge deferred uid_hash={} play_status={}", Principal.hash(owner.uid), e.status)
            }
        }
        store.link(record)
        sub?.linkedPurchaseToken?.takeIf { it != token }?.let { store.deleteToken(it) }
        audit(owner.uid, existing ?: owner, record)
        return RtdnOutcome.UPDATED
    }

    private fun recordOf(uid: String, token: String, sub: PlaySubscription): EntitlementRecord {
        val now = clock.millis()
        val state = EntitlementRules.stateOf(sub.state, sub.expiryTimeMs, now)
        return EntitlementRecord(uid, token, sub.productId, state, EntitlementRules.isPro(state), sub.expiryTimeMs, now)
    }

    private fun response(r: EntitlementRecord): EntitlementResponse {
        val issued = clock.millis()
        return EntitlementResponse(r.proActive, r.expiresAtMs, signer.sign(r, issued), r.state.wire, issued)
    }

    private fun none(): EntitlementResponse {
        val issued = clock.millis()
        return EntitlementResponse(false, 0L, signer.sign(false, SubState.NONE, 0L, issued), SubState.NONE.wire, issued)
    }

    private fun audit(uid: String, before: EntitlementRecord?, after: EntitlementRecord) {
        val was = before?.proActive == true
        val event = when {
            !was && after.proActive -> "entitlement_grant"
            was && !after.proActive -> "entitlement_revoke"
            else -> return
        }
        audit.info("{} uid_hash={} state={}", event, Principal.hash(uid), after.state.wire)
    }

    /** Retries a retryable Play failure. A call that recovers is logged at WARN. */
    private suspend fun <T> withRetry(op: String, uid: String?, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                val result = block()
                if (attempt > 0) {
                    log.warn("play {} recovered after {} retries uid_hash={}", op, attempt, uid?.let(Principal::hash) ?: "-")
                }
                return result
            } catch (e: PlayApiException) {
                if (!e.retryable || attempt >= retryDelaysMs.size) throw e
                delay(retryDelaysMs[attempt])
                attempt++
            }
        }
    }

    private fun PlayState.acknowledgeable() =
        this == PlayState.ACTIVE || this == PlayState.IN_GRACE_PERIOD || this == PlayState.CANCELED

    companion object {
        const val PRODUCT_ID = "pro"
        private val log = LoggerFactory.getLogger("kbapi.billing")
        private val audit = LoggerFactory.getLogger("kbapi.audit")
    }
}
