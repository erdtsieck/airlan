package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.discovery.Discovery
import io.github.erdtsieck.airlan.discovery.LocalAddress
import io.github.erdtsieck.airlan.discovery.hostsToScan
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.WFRAC_PORT
import io.github.erdtsieck.airlan.wfrac.WfRacClient
import io.github.erdtsieck.airlan.wfrac.WfRacException
import java.io.IOException
import java.net.InetAddress
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock

/** Schedules the moment a unit's off-timer fires. Backed by AlarmManager on the device. */
interface TimerScheduler {
    fun schedule(airconId: String, atMillis: Long, attempt: Int = 0)
    fun cancel(airconId: String)
}

/** Nothing answers as a WF-RAC module at the address the user typed. */
class NoUnitAtAddressException(host: String) : Exception("no WF-RAC module answers at $host")

/**
 * Everything the app does with units: discovery, reading, writing and the off-timers.
 * Shared by the UI and the timer receiver, so there is one client (and one request queue)
 * per unit in the process.
 */
class Repository(
    private val store: UnitStore,
    private val timers: TimerScheduler,
    private val localAddresses: () -> List<LocalAddress>,
    private val port: Int = WFRAC_PORT,
    private val clientIntervalMs: Long = 1100,
    private val timezone: () -> String = { TimeZone.getDefault().id },
    private val now: () -> Long = System::currentTimeMillis,
) {
    val units get() = store.state

    private val clients = ConcurrentHashMap<String, WfRacClient>()
    private val registered = ConcurrentHashMap.newKeySet<String>()
    private val scanMutex = Mutex()
    private var lastScan = 0L

    private fun clientFor(unit: StoredUnit): WfRacClient {
        val ids = store.state.value
        return clients.compute(unit.airconId) { _, existing ->
            existing?.takeIf { it.host == unit.host }
                ?: WfRacClient(unit.host, ids.deviceId, ids.operatorId, unit.scheme, unit.certificatePin, port, clientIntervalMs)
        }!!
    }

    private fun require(airconId: String) = store.unit(airconId) ?: throw IllegalArgumentException("unknown unit $airconId")

    /** Runs [block] against a unit; on a network failure, rescans (at most once a minute) and retries. */
    private suspend fun <T> withUnit(airconId: String, block: suspend (WfRacClient) -> T): T {
        val client = clientFor(require(airconId))
        try {
            return block(client)
        } catch (e: IOException) {
            if (now() - lastScan < 60_000) throw e
            scan()
            return block(clientFor(require(airconId)))
        } finally {
            persistTransport(airconId, client)
        }
    }

    private suspend fun persistTransport(airconId: String, client: WfRacClient) {
        val unit = store.unit(airconId) ?: return
        val scheme = client.scheme ?: return
        if (unit.scheme != scheme || unit.certificatePin != client.certificatePin) {
            store.updateUnit(airconId) { it.copy(scheme = scheme, certificatePin = client.certificatePin) }
        }
    }

    suspend fun state(airconId: String): AirconState = withUnit(airconId) { it.getStat(airconId) }

    /**
     * Applies [change] to the unit's current state and writes it. Writes need an account
     * registered with the unit; registering the same id again is idempotent.
     */
    suspend fun apply(airconId: String, change: (AirconState) -> AirconState): AirconState {
        if (airconId !in registered) {
            withUnit(airconId) { it.registerAccount(airconId, timezone()) }
            registered += airconId
        }
        return withUnit(airconId) { client ->
            val next = change(client.getStat(airconId))
            val echoed = client.setStat(airconId, next)
            next.copy(indoorTemp = echoed.indoorTemp ?: next.indoorTemp, outdoorTemp = echoed.outdoorTemp ?: next.outdoorTemp)
        }.also { if (!it.power) cancelTimer(airconId) }
    }

    /** Adds the module at [host], or updates the address of a known one. New units have no name. */
    suspend fun adopt(host: String): StoredUnit {
        val ids = store.state.value
        val probe = WfRacClient(host, ids.deviceId, ids.operatorId, port = port, minIntervalMs = clientIntervalMs)
        val airconId = try {
            probe.getDeviceInfo()
        } catch (e: IOException) {
            throw NoUnitAtAddressException(host)
        } catch (e: WfRacException) {
            throw NoUnitAtAddressException(host)
        }
        store.update { s ->
            val existing = s.units.firstOrNull { it.airconId == airconId }
            val adopted = (existing ?: StoredUnit(airconId, name = null, host = host))
                .copy(host = host, scheme = probe.scheme, certificatePin = probe.certificatePin ?: existing?.certificatePin)
            s.copy(units = if (existing == null) s.units + adopted else s.units.map { if (it.airconId == airconId) adopted else it })
        }
        clients.remove(airconId)
        return require(airconId)
    }

    /** Scans the local network for units. Returns the number of units known afterwards. */
    suspend fun scan(): Int = scanMutex.withLock {
        for (host in Discovery.findOpen(hostsToScan(localAddresses()), port)) {
            try {
                adopt(host)
            } catch (e: NoUnitAtAddressException) {
                // Something else listens on the module's port.
            }
        }
        lastScan = now()
        store.state.value.units.size
    }

    /** Adds a unit by address, as typed by the user. */
    suspend fun add(host: String): StoredUnit {
        val trimmed = host.trim()
        require(isIpv4(trimmed)) { "not an IPv4 address: $host" }
        return adopt(trimmed)
    }

    suspend fun rename(airconId: String, name: String) {
        val trimmed = name.trim().take(40)
        require(trimmed.isNotEmpty()) { "name must not be empty" }
        store.updateUnit(airconId) { it.copy(name = trimmed) }
    }

    suspend fun forget(airconId: String) {
        timers.cancel(airconId)
        store.update { s -> s.copy(units = s.units.filterNot { it.airconId == airconId }) }
        clients.remove(airconId)
    }

    suspend fun setTimer(airconId: String, minutes: Int) {
        require(minutes in 1..MAX_TIMER_MINUTES) { "minutes must be between 1 and $MAX_TIMER_MINUTES" }
        val at = now() + minutes * 60_000L
        store.updateUnit(airconId) { it.copy(offAt = at) }
        timers.schedule(airconId, at)
    }

    suspend fun cancelTimer(airconId: String) {
        if (store.unit(airconId)?.offAt == null) return
        timers.cancel(airconId)
        store.updateUnit(airconId) { it.copy(offAt = null) }
    }

    sealed interface TimerOutcome {
        data object SwitchedOff : TimerOutcome
        data object NotDue : TimerOutcome
        data class Retrying(val attempt: Int) : TimerOutcome
        data class GaveUp(val error: Exception) : TimerOutcome
    }

    /**
     * Called when a unit's alarm goes off. Switches it off, or schedules a retry: the unit may
     * be briefly unreachable, or another client may hold its 60-second write lock.
     */
    suspend fun fireTimer(airconId: String, attempt: Int): TimerOutcome {
        val unit = store.unit(airconId) ?: return TimerOutcome.NotDue
        val offAt = unit.offAt ?: return TimerOutcome.NotDue
        if (offAt > now() + 1000) {
            timers.schedule(airconId, offAt) // Rescheduled since this alarm was set.
            return TimerOutcome.NotDue
        }
        return try {
            // Runs inside a broadcast receiver, which Android gives about a minute. An
            // unreachable unit costs a timeout per transport plus a rescan, so bound the attempt.
            withTimeout(TIMER_ATTEMPT_TIMEOUT_MS) { apply(airconId) { it.copy(power = false) } }
            store.updateUnit(airconId) { it.copy(offAt = null) }
            TimerOutcome.SwitchedOff
        } catch (e: Exception) {
            if (attempt + 1 < TIMER_ATTEMPTS) {
                timers.schedule(airconId, now() + TIMER_RETRY_MS, attempt + 1)
                TimerOutcome.Retrying(attempt + 1)
            } else {
                store.updateUnit(airconId) { it.copy(offAt = null) }
                TimerOutcome.GaveUp(e)
            }
        }
    }

    /** After a reboot or update, alarms are gone: set them again. Overdue ones fire right away. */
    fun rescheduleAll() {
        for (u in store.state.value.units) u.offAt?.let { timers.schedule(u.airconId, it) }
    }

    companion object {
        const val MAX_TIMER_MINUTES = 6 * 60
        const val TIMER_ATTEMPTS = 20
        const val TIMER_RETRY_MS = 30_000L
        const val TIMER_ATTEMPT_TIMEOUT_MS = 40_000L

        fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 } &&
                runCatching { InetAddress.getByName(s) }.isSuccess
        }
    }
}
