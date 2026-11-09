package com.manjul.kbapi.billing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/** `SubscriptionNotification.notificationType` values (Play RTDN reference). */
object RtdnType {
    const val RECOVERED = 1
    const val RENEWED = 2
    const val CANCELED = 3
    const val PURCHASED = 4
    const val ON_HOLD = 5
    const val IN_GRACE_PERIOD = 6
    const val RESTARTED = 7
    const val PRICE_CHANGE_CONFIRMED = 8
    const val DEFERRED = 9
    const val PAUSED = 10
    const val PAUSE_SCHEDULE_CHANGED = 11
    const val REVOKED = 12
    const val EXPIRED = 13

    /** Not a Play type: the `voidedPurchaseNotification` case, logged as 0. */
    const val VOIDED = 0
}

/** The Pub/Sub push envelope: `{"message":{"data":"<base64>","messageId":"..."},"subscription":"..."}`. */
@Serializable
data class PubSubPush(val message: PubSubMessage, val subscription: String? = null)

@Serializable
data class PubSubMessage(val data: String? = null, val messageId: String? = null, val message_id: String? = null) {
    val id: String? get() = (messageId ?: message_id)?.takeIf { it.isNotBlank() && it.length <= 128 }
}

/** A decoded Play developer notification. [toString] never prints the purchase token. */
sealed interface RtdnMessage {
    val packageName: String?

    data class Subscription(override val packageName: String?, val type: Int, val token: String) : RtdnMessage {
        override fun toString(): String = "Subscription(type=$type)"
    }

    data class Voided(override val packageName: String?, val token: String) : RtdnMessage {
        override fun toString(): String = "Voided"
    }

    data class Test(override val packageName: String?) : RtdnMessage

    /** One-time products and anything else kb-api does not act on. */
    data class Other(override val packageName: String?) : RtdnMessage

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Decodes `message.data`. Returns null when it is not base64 JSON. Never throws. */
        fun decode(data: String?): RtdnMessage? {
            if (data.isNullOrEmpty() || data.length > MAX_DATA_CHARS) return null
            val obj = try {
                json.parseToJsonElement(String(Base64.getDecoder().decode(data), Charsets.UTF_8)) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val pkg = (obj["packageName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            (obj["subscriptionNotification"] as? JsonObject)?.let { n ->
                val type = (n["notificationType"] as? JsonPrimitive)?.intOrNull ?: return Other(pkg)
                val token = n.token() ?: return Other(pkg)
                return Subscription(pkg, type, token)
            }
            (obj["voidedPurchaseNotification"] as? JsonObject)?.let { n ->
                return n.token()?.let { Voided(pkg, it) } ?: Other(pkg)
            }
            if (obj["testNotification"] is JsonObject) return Test(pkg)
            return Other(pkg)
        }

        private fun JsonObject.token(): String? =
            (this["purchaseToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_CHARS }

        private const val MAX_DATA_CHARS = 16_384
        const val MAX_TOKEN_CHARS = 4096
    }
}
