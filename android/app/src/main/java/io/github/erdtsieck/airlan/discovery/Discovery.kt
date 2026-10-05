package io.github.erdtsieck.airlan.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.erdtsieck.airlan.wfrac.WFRAC_PORT
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** An IPv4 address of this device with its prefix length, e.g. 192.168.1.10/24. */
data class LocalAddress(val address: String, val prefixLength: Int)

// Networks up to /22 (1022 hosts) are scanned completely. For larger ones only the /24
// around our own address is; units elsewhere can be added by address. Same rule as the
// server's discovery.js.
private const val MAX_SCAN_PREFIX = 22

private fun toLong(ip: String) = ip.split('.').fold(0L) { n, part -> n * 256 + part.toInt() }
private fun toIp(n: Long) = listOf(24, 16, 8, 0).joinToString(".") { ((n shr it) and 0xff).toString() }

fun hostsToScan(addresses: List<LocalAddress>): List<String> {
    val hosts = LinkedHashSet<String>()
    for (a in addresses) {
        if (a.prefixLength > 30) continue
        val prefix = if (a.prefixLength < MAX_SCAN_PREFIX) 24 else a.prefixLength
        val size = 1L shl (32 - prefix)
        val network = toLong(a.address) / size * size
        for (i in 1 until size - 1) hosts += toIp(network + i)
    }
    addresses.forEach { hosts -= it.address }
    return hosts.toList()
}

object Discovery {
    /** IPv4 addresses on the networks a unit could be on: Wi-Fi and Ethernet. */
    fun localAddresses(context: Context): List<LocalAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return emptyList()
        val caps = cm.getNetworkCapabilities(network) ?: return emptyList()
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return emptyList()
        }
        return cm.getLinkProperties(network)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
            .map { LocalAddress(it.address.hostAddress!!, it.prefixLength) }
    }

    /** Whether the phone is on a network where the units can be: Wi-Fi or Ethernet. */
    fun onLocalNetwork(context: Context) = localAddresses(context).isNotEmpty()

    private fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    /** Probes the hosts with bounded parallelism and returns those that accept connections. */
    suspend fun findOpen(hosts: List<String>, port: Int = WFRAC_PORT, timeoutMs: Int = 1500, parallelism: Int = 128): List<String> =
        withContext(Dispatchers.IO) {
            val permits = Semaphore(parallelism)
            coroutineScope {
                hosts.map { host -> async { permits.withPermit { host.takeIf { portOpen(it, port, timeoutMs) } } } }
                    .awaitAll()
                    .filterNotNull()
            }
        }
}
