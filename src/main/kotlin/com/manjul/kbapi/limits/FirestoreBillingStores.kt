package com.manjul.kbapi.limits

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.manjul.kbapi.billing.EntitlementRecord
import com.manjul.kbapi.billing.SubState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * `entitlements/{sha256(token)}` holds one record per purchase token, with a `uid` field. The document id is a
 * hash so the token never appears in a document path, a console URL or an audit log. `entitlement_uids/{uid}`
 * points at the uid's current token doc. [link] runs in one transaction, so a token has one owner and a uid has
 * one token even with concurrent verifies on several instances (DB-006 race guidance, applied to Firestore).
 */
class FirestoreEntitlementStore(private val db: Firestore, private val clock: Clock) : EntitlementStore {
    private val records get() = db.collection("entitlements")
    private val uids get() = db.collection("entitlement_uids")

    override suspend fun entitlement(uid: String): Entitlement? = byUid(uid)?.toEntitlement(clock.millis())

    override suspend fun link(record: EntitlementRecord) {
        withContext(Dispatchers.IO) {
            db.runTransaction { tx ->
                val ref = records.document(sha256Hex(record.token))
                val uidRef = uids.document(record.uid)
                val current = tx.get(ref).get()
                val uidSnap = tx.get(uidRef).get()
                val previousOwner = current.getString("uid")?.takeIf { it != record.uid }
                val previousOwnerRef = previousOwner?.let { uids.document(it) }
                val previousOwnerSnap = previousOwnerRef?.let { tx.get(it).get() }
                val oldDocId = uidSnap.getString("doc")?.takeIf { it != ref.id }
                if (oldDocId != null) tx.delete(records.document(oldDocId))
                if (previousOwnerSnap?.getString("doc") == ref.id) tx.delete(previousOwnerRef)
                tx.set(ref, fields(record))
                tx.set(uidRef, mapOf("doc" to ref.id))
                null
            }.get()
        }
    }

    override suspend fun byToken(token: String): EntitlementRecord? = withContext(Dispatchers.IO) {
        records.document(sha256Hex(token)).get().get().toRecord()
    }

    override suspend fun byUid(uid: String): EntitlementRecord? = withContext(Dispatchers.IO) {
        val doc = uids.document(uid).get().get().getString("doc") ?: return@withContext null
        records.document(doc).get().get().toRecord()?.takeIf { it.uid == uid }
    }

    override suspend fun deleteToken(token: String) {
        withContext(Dispatchers.IO) {
            db.runTransaction { tx ->
                val ref = records.document(sha256Hex(token))
                val snap = tx.get(ref).get()
                val owner = snap.getString("uid")
                val ownerRef = owner?.let { uids.document(it) }
                val ownerSnap = ownerRef?.let { tx.get(it).get() }
                if (ownerSnap?.getString("doc") == ref.id) tx.delete(ownerRef)
                if (snap.exists()) tx.delete(ref)
                null
            }.get()
        }
    }

    override suspend fun deleteUid(uid: String) {
        withContext(Dispatchers.IO) {
            db.runTransaction { tx ->
                val uidRef = uids.document(uid)
                val doc = tx.get(uidRef).get().getString("doc")
                if (doc != null) tx.delete(records.document(doc))
                tx.delete(uidRef)
                null
            }.get()
            // A record whose uid pointer was lost (partial write) is still found by its uid field.
            records.whereEqualTo("uid", uid).get().get().documents.forEach { it.reference.delete().get() }
        }
    }

    /** The token is stored so RTDN and acknowledge can use it; the doc id never carries it. */
    private fun fields(r: EntitlementRecord) = mapOf(
        "uid" to r.uid,
        "token" to r.token,
        "product_id" to r.productId,
        "state" to r.state.wire,
        "pro_active" to r.proActive,
        "expires_at_ms" to r.expiresAtMs,
        "updated_at_ms" to r.updatedAtMs,
    )

    private fun DocumentSnapshot.toRecord(): EntitlementRecord? {
        if (!exists()) return null
        return EntitlementRecord(
            uid = getString("uid") ?: return null,
            token = getString("token") ?: return null,
            productId = getString("product_id").orEmpty(),
            state = SubState.fromWire(getString("state")) ?: SubState.NONE,
            proActive = getBoolean("pro_active") ?: false,
            expiresAtMs = getLong("expires_at_ms") ?: 0L,
            updatedAtMs = getLong("updated_at_ms") ?: 0L,
        )
    }
}

/** `integrity_verdicts/{uid}` with `ok` and `checked_at_ms`. */
class FirestoreIntegrityVerdictCache(private val db: Firestore) : IntegrityVerdictCache {
    private fun ref(uid: String) = db.collection("integrity_verdicts").document(uid)

    override suspend fun verdict(uid: String): IntegrityVerdict? = withContext(Dispatchers.IO) {
        val snap = ref(uid).get().get()
        if (!snap.exists()) return@withContext null
        IntegrityVerdict(snap.getBoolean("ok") ?: false, Instant.ofEpochMilli(snap.getLong("checked_at_ms") ?: 0L))
    }

    override suspend fun record(uid: String, atMs: Long) {
        withContext(Dispatchers.IO) { ref(uid).set(mapOf("ok" to true, "checked_at_ms" to atMs)).get() }
    }

    override suspend fun deleteUid(uid: String) {
        withContext(Dispatchers.IO) { ref(uid).delete().get() }
    }
}

/**
 * Pub/Sub delivers at least once, so RTDN processing is idempotent on the messageId. A message is marked
 * only after it was processed, so a delivery that failed (503) is processed again on retry.
 */
interface RtdnDedupe {
    suspend fun seen(messageId: String): Boolean

    suspend fun mark(messageId: String)
}

class InMemoryRtdnDedupe : RtdnDedupe {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override suspend fun seen(messageId: String) = messageId in ids
    override suspend fun mark(messageId: String) {
        ids += messageId
    }
}

/** `rtdn_seen/{sha256(messageId)}` with `expires_at` for a Firestore TTL policy (7 days, deploy/README.md). */
class FirestoreRtdnDedupe(private val db: Firestore, private val clock: Clock) : RtdnDedupe {
    private fun ref(id: String) = db.collection("rtdn_seen").document(sha256Hex(id))

    override suspend fun seen(messageId: String): Boolean = withContext(Dispatchers.IO) {
        ref(messageId).get().get().exists()
    }

    override suspend fun mark(messageId: String) {
        val expires = clock.instant().plus(Duration.ofDays(7))
        withContext(Dispatchers.IO) {
            ref(messageId).set(mapOf("expires_at" to Timestamp.ofTimeSecondsAndNanos(expires.epochSecond, 0))).get()
        }
    }
}
