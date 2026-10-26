package com.manjul.kbapi.limits

import com.manjul.kbapi.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The per-IP bucket key. Behind the external HTTPS load balancer (deploy/README.md §6) XFF is `<client>, <lb>`. */
class ClientIpTest {
    @Test
    fun `behind the load balancer the client is the second to last entry`() {
        assertEquals("203.0.113.7", clientIpFrom("203.0.113.7, 34.120.0.1", trustedHops = 2))
    }

    @Test
    fun `entries a client prepends cannot pick the key`() {
        assertEquals("203.0.113.7", clientIpFrom("1.1.1.1, 9.9.9.9, 203.0.113.7, 34.120.0.1", trustedHops = 2))
    }

    @Test
    fun `different clients through one load balancer get different keys`() {
        val lb = "34.120.0.1"
        val a = clientIpFrom("198.51.100.1, $lb", 2)
        val b = clientIpFrom("198.51.100.2, $lb", 2)
        assertEquals(false, a == b)
    }

    @Test
    fun `one hop takes the last entry`() {
        assertEquals("203.0.113.7", clientIpFrom("1.1.1.1, 203.0.113.7", trustedHops = 1))
    }

    @Test
    fun `fewer entries than hops falls back to the first entry, none to null`() {
        assertEquals("203.0.113.7", clientIpFrom("203.0.113.7", trustedHops = 2))
        assertNull(clientIpFrom(null, 2))
        assertNull(clientIpFrom(" , ", 2))
    }

    @Test
    fun `TRUSTED_PROXY_HOPS defaults to the load balancer shape and is validated`() {
        val dev = mapOf("AUTH_MODE" to "dev")
        assertEquals(2, Config.fromEnv(dev).trustedProxyHops)
        assertEquals(1, Config.fromEnv(dev + ("TRUSTED_PROXY_HOPS" to "1")).trustedProxyHops)
        assertFailsWith<IllegalStateException> { Config.fromEnv(dev + ("TRUSTED_PROXY_HOPS" to "0")) }
    }
}
