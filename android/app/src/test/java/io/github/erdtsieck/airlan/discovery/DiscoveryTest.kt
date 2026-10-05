package io.github.erdtsieck.airlan.discovery

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors test/discovery.test.js. */
class DiscoveryTest {
    @Test
    fun `scans a 24 completely, except our own address`() {
        val hosts = hostsToScan(listOf(LocalAddress("192.168.68.118", 24)))
        assertEquals(253, hosts.size)
        assertTrue("192.168.68.1" in hosts && "192.168.68.254" in hosts)
        assertFalse("192.168.68.118" in hosts || "192.168.68.0" in hosts || "192.168.68.255" in hosts)
    }

    @Test
    fun `scans a 22 completely`() {
        val hosts = hostsToScan(listOf(LocalAddress("10.1.6.20", 22)))
        assertEquals(1021, hosts.size)
        assertTrue("10.1.4.1" in hosts && "10.1.7.254" in hosts)
    }

    @Test
    fun `limits a larger network to the 24 around our own address`() {
        val hosts = hostsToScan(listOf(LocalAddress("10.20.30.40", 16)))
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.20.30.") })
    }

    @Test
    fun `ignores point-to-point links and merges addresses on one network`() {
        val hosts = hostsToScan(listOf(LocalAddress("10.8.0.2", 32), LocalAddress("192.168.1.10", 24), LocalAddress("192.168.1.11", 24)))
        assertEquals(252, hosts.size)
    }

    @Test
    fun `findOpen finds listening hosts and gives up on silent ones`() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val started = System.currentTimeMillis()
            // 192.0.2.0/24 is TEST-NET-1: nothing answers there, so only the timeout ends the probe.
            val open = Discovery.findOpen(listOf("192.0.2.1", "127.0.0.1", "192.0.2.2"), server.localPort, timeoutMs = 300, parallelism = 2)
            assertEquals(listOf("127.0.0.1"), open)
            assertTrue(System.currentTimeMillis() - started < 3000)
        }
    }
}
