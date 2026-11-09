package com.manjul.kbapi.billing

import com.manjul.kbapi.limits.InMemoryEntitlementStore
import com.manjul.kbapi.testing.BillingKeys
import com.manjul.kbapi.testing.DAY_MS
import com.manjul.kbapi.testing.FakePlayApi
import com.manjul.kbapi.testing.MutableClock
import com.manjul.kbapi.testing.PRO
import com.manjul.kbapi.testing.sub
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementServiceTest {
    private val clock = MutableClock()
    private val play = FakePlayApi()
    private val store = InMemoryEntitlementStore()
    private val service = EntitlementService(play, store, BillingKeys.signer, clock, retryDelaysMs = listOf(0L, 0L))
    private val now get() = clock.millis()

    private suspend fun ok(uid: String = "uid-a", token: String = "tok-1"): EntitlementResponse =
        assertIs<VerifyResult.Ok>(service.verify(uid, token, PRO)).response

    @Test
    fun `ACTIVE gives pro and acknowledges once`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        val r = ok()
        assertTrue(r.proActive)
        assertEquals("active", r.state)
        assertEquals(now + 30 * DAY_MS, r.expiresAt)
        assertEquals(now, r.issuedAt)
        assertEquals(listOf(PRO to "tok-1"), play.acks)
        ok()
        assertEquals(1, play.acks.size, "an acknowledged purchase is not acknowledged again")
    }

    @Test
    fun `an acknowledged sub is not acknowledged`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE, acknowledged = true)
        ok()
        assertTrue(play.acks.isEmpty())
    }

    @Test
    fun `a wrong product, a Play null and a canceled pending purchase are InvalidToken`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE, productId = "other")
        assertEquals(VerifyResult.InvalidToken, service.verify("uid-a", "tok-1", PRO))
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        assertEquals(VerifyResult.InvalidToken, service.verify("uid-a", "tok-1", "other"))
        assertEquals(VerifyResult.InvalidToken, service.verify("uid-a", "missing", PRO))
        play.subs["tok-2"] = sub(PlayState.PENDING_PURCHASE_CANCELED)
        assertEquals(VerifyResult.InvalidToken, service.verify("uid-a", "tok-2", PRO))
        assertNull(store.byUid("uid-a"), "nothing is linked for an invalid token")
    }

    @Test
    fun `GRACE gives pro with state grace`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.IN_GRACE_PERIOD)
        val r = ok()
        assertTrue(r.proActive)
        assertEquals("grace", r.state)
    }

    @Test
    fun `ON_HOLD and PAUSED give no pro`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ON_HOLD)
        assertEquals(false to "hold", ok().let { it.proActive to it.state })
        play.subs["tok-1"] = sub(PlayState.PAUSED)
        assertEquals(false to "paused", ok().let { it.proActive to it.state })
        play.subs["tok-1"] = sub(PlayState.PENDING)
        assertEquals(false to "pending", ok().let { it.proActive to it.state })
    }

    @Test
    fun `CANCELED is pro before expiry and expired after`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.CANCELED, expiryMs = now + DAY_MS)
        assertEquals(true to "active", ok().let { it.proActive to it.state })
        clock.advance(Duration.ofDays(2))
        assertEquals(false to "expired", ok().let { it.proActive to it.state })
        play.subs["tok-1"] = sub(PlayState.EXPIRED, expiryMs = now - DAY_MS)
        assertEquals(false to "expired", ok().let { it.proActive to it.state })
    }

    @Test
    fun `an ACTIVE sub past its expiry time is not pro`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE, expiryMs = now - 1)
        assertFalse(ok().proActive)
    }

    @Test
    fun `verify of the same token from uid B moves the link from uid A`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        ok(uid = "uid-a")
        assertEquals("uid-a", store.byToken("tok-1")?.uid)
        ok(uid = "uid-b")
        assertEquals("uid-b", store.byToken("tok-1")?.uid)
        assertNull(store.byUid("uid-a"))
        assertTrue(service.current("uid-a").let { !it.proActive && it.state == "none" })
        assertTrue(service.current("uid-b").proActive)
    }

    @Test
    fun `the signature covers the decision 4 payload`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        val r = ok()
        val payload = "v1|${r.proActive}|${r.state}|${r.expiresAt}|${r.issuedAt}"
        assertTrue(BillingKeys.verifies(payload, r.signature))
    }

    @Test
    fun `current without a record is a signed none`() = runBlocking {
        val r = service.current("nobody")
        assertFalse(r.proActive)
        assertEquals("none", r.state)
        assertEquals(0L, r.expiresAt)
        assertTrue(BillingKeys.verifies("v1|false|none|0|${r.issuedAt}", r.signature))
    }

    @Test
    fun `current recomputes a canceled record after expiry without Play`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.CANCELED, expiryMs = now + DAY_MS)
        ok()
        clock.advance(Duration.ofDays(2))
        val reads = play.reads
        val r = service.current("uid-a")
        assertEquals(false to "expired", r.proActive to r.state)
        assertEquals(reads, play.reads)
    }

    @Test
    fun `a Play call that recovers on retry still verifies`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        play.failNext = 1
        assertTrue(ok().proActive)
    }

    @Test
    fun `Play down is Unavailable`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        play.down = true
        assertEquals(VerifyResult.Unavailable, service.verify("uid-a", "tok-1", PRO))
    }

    @Test
    fun `onRtdn revokes, ignores unknown tokens and follows linkedPurchaseToken`() = runBlocking {
        play.subs["tok-1"] = sub(PlayState.ACTIVE)
        ok()
        play.subs["tok-1"] = sub(PlayState.EXPIRED, expiryMs = now - 1)
        service.onRtdn("tok-1", RtdnType.REVOKED)
        assertEquals(SubState.REVOKED, store.byToken("tok-1")?.state)
        assertFalse(service.current("uid-a").proActive)

        assertEquals(RtdnOutcome.IGNORED, service.onRtdn("unknown", RtdnType.RENEWED))
        assertNull(store.byToken("unknown"))

        play.subs["tok-2"] = sub(PlayState.ACTIVE, linked = "tok-1", acknowledged = true)
        ok(token = "tok-2")
        assertNull(store.byToken("tok-1"), "the replaced token's record is removed")
        assertEquals("tok-2", store.byUid("uid-a")?.token)
    }
}
