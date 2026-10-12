package io.github.erdtsieck.airlan.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.erdtsieck.airlan.wfrac.WFRAC_PORT
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** An IPv4 address of this device with its prefix length, e.g. 192.168.1.10/24. */
data class LocalAddress(val address: String, val prefixLength: Int)

/** A module that announced itself by mDNS: its airconId (the MAC address it uses as instance name) and address. */
data class Announced(val airconId: String, val host: String)

/** mDNS service type of WF-RAC modules; the instance name is the module's MAC address. */
const val WFRAC_SERVICE = "_beaver._tcp"

/** The airconId in an mDNS instance name, or null when it is not a WF-RAC module's. */
fun airconIdOf(serviceName: String): String? = serviceName.lowercase().takeIf { it.matches(Regex("[0-9a-f]{12}")) }

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
    // Android 17 (API 37) made the local network a runtime permission. Before that,
    // INTERNET covers it and the permission does not exist. Android 16 only restricts the
    // local network for apps opted in to the RESTRICT_LOCAL_NETWORK compat change, which
    // is off by default whatever the target SDK; there NEARBY_WIFI_DEVICES (declared in
    // the manifest so it can be granted in Settings) lifts it. Asking every Android 16
    // user for it would block them on a prompt for access they already have.
    private const val LOCAL_NETWORK_SDK = 37
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Whether the app may talk to devices on the local network. Always on before Android 17. */
    fun localNetworkAllowed(context: Context) =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
            ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

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

    /**
     * Asks the network for WF-RAC modules by mDNS and returns those that answered within
     * [timeoutMs] with an address. Quick and names the units, but not every module answers
     * every time, so a port scan stays the fallback. Never throws.
     */
    suspend fun announced(context: Context, timeoutMs: Long = 3000): List<Announced> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = ConcurrentHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                airconIdOf(info.serviceName)?.let { found[it] = info }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            nsd.discoverServices(WFRAC_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            return emptyList()
        }
        try {
            delay(timeoutMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return found.mapNotNull { (id, info) -> withTimeoutOrNull(2000) { resolve(nsd, info) }?.let { Announced(id, it) } }
    }

    /** The IPv4 address of a found service. */
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            suspendCancellableCoroutine { cont ->
                val direct = Executor { it.run() }
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceUpdated(updated: NsdServiceInfo) {
                        val address = updated.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
                        if (cont.isActive) cont.resume(address)
                        runCatching { nsd.unregisterServiceInfoCallback(this) }
                    }
                    override fun onServiceLost() = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                nsd.registerServiceInfoCallback(info, direct, callback)
                cont.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
            }
        } else {
            suspendCancellableCoroutine { cont ->
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val address = (info.host as? Inet4Address)?.hostAddress
                            if (cont.isActive) cont.resume(address)
                        }
                    },
                )
            }
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
