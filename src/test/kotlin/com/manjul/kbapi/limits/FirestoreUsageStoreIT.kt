package com.manjul.kbapi.limits

import com.google.cloud.firestore.FirestoreOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Runs only against the Firestore emulator (`FIRESTORE_EMULATOR_HOST`). */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class FirestoreUsageStoreIT {
    private val db = FirestoreOptions.getDefaultInstance().toBuilder().setProjectId("kb-test-project").build().service
    private val store = FirestoreUsageStore(db)
    private val uid = "it-" + UUID.randomUUID()

    @Test
    fun `reserve is idempotent, capped and refundable`() = runBlocking {
        assertEquals(Reservation.Ok(1), store.reserve(uid, "20261026", "202610", "a", 2, null))
        assertEquals(Reservation.Duplicate(1), store.reserve(uid, "20261026", "202610", "a", 2, null))
        assertEquals(Reservation.Ok(0), store.reserve(uid, "20261026", "202610", "b", 2, null))
        assertIs<Reservation.Exhausted>(store.reserve(uid, "20261026", "202610", "c", 2, null))
        store.refund(uid, "20261026", "202610", "a")
        store.refund(uid, "20261026", "202610", "a")
        assertEquals(1, store.used(uid, "20261026"))
        assertEquals(1, store.usedMonth(uid, "202610"))
    }

    @Test
    fun `replays of a reqId are capped and a refund after a replay keeps the action counted`() = runBlocking {
        assertEquals(Reservation.Ok(0), store.reserve(uid, "20261026", "202610", "x", 1, null))
        repeat(MAX_ATTEMPTS_PER_REQ_ID - 1) { assertEquals(Reservation.Duplicate(0), store.reserve(uid, "20261026", "202610", "x", 1, null)) }
        assertIs<Reservation.Exhausted>(store.reserve(uid, "20261026", "202610", "x", 1, null))
        store.refund(uid, "20261026", "202610", "x")
        assertEquals(1, store.used(uid, "20261026"))
        assertEquals(1, store.usedMonth(uid, "202610"))
        assertIs<Reservation.Exhausted>(store.reserve(uid, "20261026", "202610", "x", 1, null))
    }

    @Test
    fun `spend store sums buckets`() = runBlocking {
        val spend = FirestoreSpendStore(db)
        val key = "it-" + UUID.randomUUID()
        spend.add(key, 1.5)
        spend.add(key, 2.0)
        assertEquals(3.5, spend.get(key), 1e-9)
    }
}
