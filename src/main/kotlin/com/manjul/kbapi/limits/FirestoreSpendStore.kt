package com.manjul.kbapi.limits

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hourly spend buckets in `spend/{yyyyMMddHH}` (`usd`), incremented atomically. */
class FirestoreSpendStore(private val db: Firestore) : SpendStore {
    private fun ref(hourKey: String) = db.collection("spend").document(hourKey)

    override suspend fun add(hourKey: String, usd: Double) {
        withContext(Dispatchers.IO) {
            ref(hourKey).set(mapOf("usd" to FieldValue.increment(usd)), SetOptions.merge()).get()
        }
    }

    override suspend fun get(hourKey: String): Double = withContext(Dispatchers.IO) {
        ref(hourKey).get().get().getDouble("usd") ?: 0.0
    }
}
