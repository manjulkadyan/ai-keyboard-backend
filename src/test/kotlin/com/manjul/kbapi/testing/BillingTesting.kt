package com.manjul.kbapi.testing

import com.manjul.kbapi.Services
import com.manjul.kbapi.account.FirebaseUserDeleter
import com.manjul.kbapi.billing.EntitlementSigner
import com.manjul.kbapi.billing.PlayApiException
import com.manjul.kbapi.billing.PlayDeveloperApi
import com.manjul.kbapi.billing.PlayState
import com.manjul.kbapi.billing.PlaySubscription
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** A Play Developer API stand-in. Retryable failures are queued with [failNext]; [down] fails every call. */
class FakePlayApi : PlayDeveloperApi {
    val subs = mutableMapOf<String, PlaySubscription>()
    val acks = CopyOnWriteArrayList<Pair<String, String>>()
    var reads = 0
    var failNext = 0
    var down = false

    override suspend fun subscription(token: String): PlaySubscription? {
        reads++
        if (down) throw PlayApiException(retryable = true, status = 503)
        if (failNext > 0) {
            failNext--
            throw PlayApiException(retryable = true, status = 503)
        }
        return subs[token]
    }

    override suspend fun acknowledge(productId: String, token: String) {
        if (down) throw PlayApiException(retryable = true, status = 503)
        acks += productId to token
        subs[token]?.let { subs[token] = it.copy(acknowledged = true) }
    }
}

/** Records Firebase user deletions. A repeat succeeds, like the real deleter on USER_NOT_FOUND. */
class FakeUserDeleter : FirebaseUserDeleter {
    val deleted = CopyOnWriteArrayList<String>()

    override suspend fun delete(uid: String) {
        deleted += uid
    }
}

object BillingKeys {
    /** TEST-ONLY EC P-256 key pair, generated in memory on every run (SecretScanTest bans committed keys). */
    val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    val signer = EntitlementSigner(Base64.getEncoder().encodeToString(keyPair.private.encoded))

    /** The client's check (`:core:billing` EntitlementSignature), repeated here. */
    fun verifies(payload: String, signatureB64: String, key: PublicKey = keyPair.public): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signatureB64))
        }
    }.getOrDefault(false)
}

const val PRO = "pro"
const val DAY_MS = 86_400_000L

fun sub(
    state: PlayState,
    expiryMs: Long = TestKeys.NOW.toEpochMilli() + 30 * DAY_MS,
    productId: String = PRO,
    acknowledged: Boolean = false,
    linked: String? = null,
) = PlaySubscription(state, productId, expiryMs, acknowledged, linked)

/** Services with billing wired to fakes: the given Play API, in-memory stores, the test signer and Pub/Sub key. */
fun billingServices(
    play: FakePlayApi = FakePlayApi(),
    deleter: FakeUserDeleter = FakeUserDeleter(),
    base: Services = testServices(),
): Services = base.copy(
    play = play,
    signer = BillingKeys.signer,
    pushAuth = TestKeys.pubSubVerifier(),
    userDeleter = deleter,
    playRetryDelaysMs = listOf(0L, 0L),
)
