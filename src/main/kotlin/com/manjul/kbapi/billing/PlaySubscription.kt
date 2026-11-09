package com.manjul.kbapi.billing

/** `subscriptionState` of `purchases.subscriptionsv2.get`, without the `SUBSCRIPTION_STATE_` prefix. */
enum class PlayState {
    ACTIVE, IN_GRACE_PERIOD, ON_HOLD, PAUSED, CANCELED, EXPIRED, PENDING, PENDING_PURCHASE_CANCELED, UNKNOWN;

    companion object {
        fun fromApi(value: String?): PlayState =
            entries.firstOrNull { "SUBSCRIPTION_STATE_${it.name}" == value || it.name == value } ?: UNKNOWN
    }
}

/**
 * The fields kb-api reads from a Play subscription purchase. [expiryTimeMs] is the latest line item expiry.
 * [toString] leaves out [linkedPurchaseToken]: purchase tokens are credentials (OPS-001).
 */
data class PlaySubscription(
    val state: PlayState,
    val productId: String,
    val expiryTimeMs: Long,
    val acknowledged: Boolean,
    val linkedPurchaseToken: String?,
) {
    override fun toString(): String =
        "PlaySubscription(state=$state, productId=$productId, expiryTimeMs=$expiryTimeMs, acknowledged=$acknowledged, " +
            "linked=${linkedPurchaseToken != null})"
}

/** kb-api subscription states, with the same wire strings as the client's `SubState` (S4 Task 2). */
enum class SubState(val wire: String) {
    NONE("none"), PENDING("pending"), ACTIVE("active"), GRACE("grace"), ON_HOLD("hold"), PAUSED("paused"),
    EXPIRED("expired"), REVOKED("revoked"),
    ;

    companion object {
        fun fromWire(wire: String?): SubState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One purchase token linked to one uid (decision 3). [proActive] and [state] are the verdict at [updatedAtMs].
 * A CANCELED record is re-evaluated against the clock on read (see [EntitlementRecord.at]).
 * [toString] never prints the uid or the token.
 */
data class EntitlementRecord(
    val uid: String,
    val token: String,
    val productId: String,
    val state: SubState,
    val proActive: Boolean,
    val expiresAtMs: Long,
    val updatedAtMs: Long,
) {
    /** The verdict at [nowMs]: anything that was Pro stops being Pro once [expiresAtMs] has passed. */
    fun at(nowMs: Long): EntitlementRecord =
        if (proActive && expiresAtMs <= nowMs) copy(proActive = false, state = SubState.EXPIRED) else this

    override fun toString(): String =
        "EntitlementRecord(productId=$productId, state=$state, proActive=$proActive, expiresAtMs=$expiresAtMs, " +
            "updatedAtMs=$updatedAtMs)"
}

/** Maps a Play state to the kb-api verdict (plan Task 3 interfaces). Pro needs an expiry in the future. */
object EntitlementRules {
    fun stateOf(play: PlayState, expiryMs: Long, nowMs: Long): SubState = when (play) {
        PlayState.ACTIVE -> if (expiryMs > nowMs) SubState.ACTIVE else SubState.EXPIRED
        PlayState.IN_GRACE_PERIOD -> if (expiryMs > nowMs) SubState.GRACE else SubState.EXPIRED
        PlayState.CANCELED -> if (expiryMs > nowMs) SubState.ACTIVE else SubState.EXPIRED
        PlayState.ON_HOLD -> SubState.ON_HOLD
        PlayState.PAUSED -> SubState.PAUSED
        PlayState.EXPIRED -> SubState.EXPIRED
        PlayState.PENDING -> SubState.PENDING
        PlayState.PENDING_PURCHASE_CANCELED, PlayState.UNKNOWN -> SubState.NONE
    }

    fun isPro(state: SubState): Boolean = state == SubState.ACTIVE || state == SubState.GRACE
}
