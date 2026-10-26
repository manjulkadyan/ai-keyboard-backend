package com.manjul.kbapi.limits

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Firestore usage counters: `usage/{uid}_{yyyymmdd}` (`count`, `req_ids` holding the
 * [MAX_REQ_IDS] most recent entries, one per model call) and `usage_m/{uid}_{yyyymm}` (`count`), both updated in one
 * transaction so concurrent instances cannot over-count.
 */
class FirestoreUsageStore(private val db: Firestore) : UsageStore {
    private fun dayRef(uid: String, day: String) = db.collection("usage").document("${uid}_$day")
    private fun monthRef(uid: String, month: String) = db.collection("usage_m").document("${uid}_$month")

    override suspend fun reserve(
        uid: String,
        day: String,
        month: String,
        reqId: String,
        dailyLimit: Int,
        monthlyLimit: Int?,
    ): Reservation = withContext(Dispatchers.IO) {
        db.runTransaction { tx ->
            val dRef = dayRef(uid, day)
            val mRef = monthRef(uid, month)
            val dSnap = tx.get(dRef).get()
            val mSnap = tx.get(mRef).get()
            val dayCount = dSnap.getLong("count")?.toInt() ?: 0
            val monthCount = mSnap.getLong("count")?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val reqIds = (dSnap.get("req_ids") as? List<String>).orEmpty()
            val r = UsageKeys.decide(day, month, dayCount, monthCount, reqIds.count { it == reqId }, dailyLimit, monthlyLimit)
            when (r) {
                is Reservation.Ok -> {
                    tx.set(dRef, mapOf("count" to dayCount + 1, "req_ids" to reqIdsAfter(reqIds, reqId, r)), SetOptions.merge())
                    tx.set(mRef, mapOf("count" to monthCount + 1), SetOptions.merge())
                }
                is Reservation.Duplicate -> tx.set(dRef, mapOf("req_ids" to reqIdsAfter(reqIds, reqId, r)), SetOptions.merge())
                is Reservation.Exhausted -> Unit
            }
            r
        }.get()
    }

    override suspend fun refund(uid: String, day: String, month: String, reqId: String) {
        withContext(Dispatchers.IO) {
            db.runTransaction { tx ->
                val dRef = dayRef(uid, day)
                val mRef = monthRef(uid, month)
                val dSnap = tx.get(dRef).get()
                val mSnap = tx.get(mRef).get()
                @Suppress("UNCHECKED_CAST")
                val reqIds = (dSnap.get("req_ids") as? List<String>).orEmpty()
                if (reqId in reqIds) {
                    val dayCount = dSnap.getLong("count")?.toInt() ?: 0
                    val monthCount = mSnap.getLong("count")?.toInt() ?: 0
                    tx.set(dRef, mapOf("count" to (dayCount - 1).coerceAtLeast(0), "req_ids" to reqIds.filter { it != reqId }), SetOptions.merge())
                    tx.set(mRef, mapOf("count" to (monthCount - 1).coerceAtLeast(0)), SetOptions.merge())
                }
                null
            }.get()
        }
    }

    override suspend fun used(uid: String, day: String): Int = withContext(Dispatchers.IO) {
        dayRef(uid, day).get().get().getLong("count")?.toInt() ?: 0
    }

    override suspend fun usedMonth(uid: String, month: String): Int = withContext(Dispatchers.IO) {
        monthRef(uid, month).get().get().getLong("count")?.toInt() ?: 0
    }
}
