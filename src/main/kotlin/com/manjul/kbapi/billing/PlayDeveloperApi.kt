package com.manjul.kbapi.billing

/**
 * The Google Play Developer API calls kb-api makes. The package name comes from config.
 * Implementations throw [PlayApiException] on a failure other than "no such purchase".
 */
interface PlayDeveloperApi {
    /** `purchases.subscriptionsv2.get`. Null means 404 or 410: the token is unknown or too old. */
    suspend fun subscription(token: String): PlaySubscription?

    /** `purchases.subscriptions.acknowledge`. */
    suspend fun acknowledge(productId: String, token: String)
}

/**
 * A failed Play call. The message is the HTTP status only: Play error bodies can quote the purchase token,
 * so they are never kept. The stack trace is not captured.
 */
class PlayApiException(val retryable: Boolean, val status: Int) :
    RuntimeException("play_api_$status", null, false, false)
