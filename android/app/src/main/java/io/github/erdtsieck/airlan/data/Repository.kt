package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.discovery.Announced
import io.github.erdtsieck.airlan.discovery.Discovery
import io.github.erdtsieck.airlan.discovery.LocalAddress
import io.github.erdtsieck.airlan.discovery.hostsToScan
import io.github.erdtsieck.airlan.wfrac.AirconClient
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.DemoClient
import io.github.erdtsieck.airlan.wfrac.WFRAC_PORT
import io.github.erdtsieck.airlan.wfrac.WfRacClient
import io.github.erdtsieck.airlan.wfrac.WfRacException
import java.io.IOException
import java.net.InetAddress
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Schedules the moment a unit's off-timer fires. Backed by AlarmManager on the device. It
 * carries no state of its own: what to do when it fires is read from the stored [OffTimer].
 */
interface TimerScheduler {
    fun schedule(airconId: String, atMillis: Long)
    fun cancel(airconId: String)
}

/** Nothing answers as a WF-RAC module at the address the user typed. */
class NoUnitAtAddressException(host: String) : Exception("no WF-RAC module answers at $host")

/** The user has not allowed the app on the local network (Android 17+), so real units are out of reach. */
class LocalNetworkNotAllowedException : Exception("the local network permission is not granted")

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
    private val localNetworkAllowed: () -> Boolean = { true },
    /** Modules that announce themselves by mDNS (see [Discovery.announced]). */
    private val announced: suspend () -> List<Announced> = { emptyList() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    val units get() = store.state

    /** Whether real units can be reached at all: the user allowed the app on the local network. */
    val networkAllowed get() = localNetworkAllowed()

    /**
     * Whether [unit] waits for the local network permission. Real units are not scanned or
     * contacted without it (connections would only time out); the demo unit needs no network.
     */
    fun needsNetworkPermission(unit: StoredUnit) = !unit.demo && !networkAllowed

    private fun requireNetwork() {
        if (!networkAllowed) throw LocalNetworkNotAllowedException()
    }

    /** A client together with the address it was made for, so a unit that moved gets a new one. */
    private class Connection(val host: String, val client: AirconClient)

    private val clients = ConcurrentHashMap<String, Connection>()
    private val registered = ConcurrentHashMap.newKeySet<String>()
    private val scanMutex = Mutex()
    private val migrateMutex = Mutex()
    private val firing = ConcurrentHashMap<String, Mutex>()
    private var lastScan = 0L

    private fun clientFor(unit: StoredUnit): AirconClient =
        clients.compute(unit.airconId) { _, existing ->
            existing?.takeIf { it.host == unit.host } ?: Connection(unit.host, newClient(unit))
        }!!.client

    /** The one place that decides how a stored unit is reached: over the network, or simulated. */
    private fun newClient(unit: StoredUnit): AirconClient {
        if (unit.demo) return DemoClient(now)
        val ids = store.state.value
        return WfRacClient(unit.host, ids.deviceId, unit.scheme, unit.certificatePin, port, clientIntervalMs, now = now)
    }

    private fun require(airconId: String) = store.unit(airconId) ?: throw IllegalArgumentException("unknown unit $airconId")

    /** Runs [block] against a unit; on a network failure, looks for the unit and retries if it moved. */
    private suspend fun <T> withUnit(airconId: String, block: suspend (AirconClient) -> T): T {
        val unit = require(airconId)
        if (needsNetworkPermission(unit)) throw LocalNetworkNotAllowedException()
        val client = clientFor(unit)
        try {
            return block(client)
        } catch (e: IOException) {
            if (unit.demo || !relocate(unit)) throw e
            return block(clientFor(require(airconId)))
        } finally {
            persistTransport(airconId, client)
        }
    }

    /**
     * Looks for a unit that stopped answering, in case DHCP gave it another address: asks mDNS
     * first, which names the unit directly, and falls back to a full scan (at most once a
     * minute). Returns whether the unit is now known at a different address.
     */
    private suspend fun relocate(unit: StoredUnit): Boolean {
        val found = announced().firstOrNull { it.airconId == unit.airconId }
        when {
            found != null && found.host != unit.host -> runCatching { adopt(found.host) }
            found == null && now() - lastScan >= 60_000 -> scan()
        }
        val host = store.unit(unit.airconId)?.host
        return host != null && host != unit.host
    }

    private suspend fun persistTransport(airconId: String, client: AirconClient) {
        val unit = store.unit(airconId) ?: return
        val scheme = client.scheme ?: return
        if (unit.scheme != scheme || unit.certificatePin != client.certificatePin) {
            store.updateUnit(airconId) { it.copy(scheme = scheme, certificatePin = client.certificatePin) }
        }
    }

    suspend fun state(airconId: String): AirconState = withUnit(airconId) { it.getStat(airconId) }

    /** Writes need an account registered with the unit; registering the same id again is idempotent. */
    /**
     * Writes need an account registered with the unit; registering the same id again is
     * idempotent. A unit this installation registered its own account with (AirLAN 0.1.1 and
     * older) is moved to the shared one instead, freeing the old slot first so that it also
     * works on a full unit; see [AirconClient.migrateAccount].
     */
    private suspend fun register(airconId: String) = migrateMutex.withLock {
        val legacy = store.state.value.legacyOperatorId
        val unit = require(airconId)
        if (legacy != null && !unit.accountMigrated && !unit.demo) {
            withUnit(airconId) { it.migrateAccount(airconId, legacy, timezone()) }
            store.updateUnit(airconId) { it.copy(accountMigrated = true) }
        } else {
            withUnit(airconId) { it.registerAccount(airconId, timezone()) }
        }
        registered += airconId
    }

    /**
     * Registers with a unit the user claims (adds or names), so a unit without a free account
     * slot shows up then and not at the first switch. Throws only for a full unit
     * ([WfRacException.NO_ACCOUNT_SLOT]); anything else is left for the first write.
     */
    suspend fun claim(airconId: String) {
        try {
            register(airconId)
        } catch (e: WfRacException) {
            if (e.code == WfRacException.NO_ACCOUNT_SLOT) throw e
        } catch (e: Exception) {
            // Unreachable, or no permission: the first write registers.
        }
    }

    /**
     * Applies [change] to the unit's current state and writes it. On a refusal it finds out why:
     *  - another client holds the write lock: waits for it to lapse when that takes at most
     *    [waitUpToS] (telling [onWait] how long) and tries once more, or throws
     *    [WfRacException.UNIT_BUSY];
     *  - otherwise our account may be gone (deleted from elsewhere): registers again, which
     *    throws [WfRacException.NO_ACCOUNT_SLOT] when the unit is full, and tries once more.
     */
    suspend fun apply(
        airconId: String,
        waitUpToS: Int = WRITE_WAIT_S,
        onWait: (seconds: Int) -> Unit = {},
        change: (AirconState) -> AirconState,
    ): AirconState {
        if (airconId !in registered) register(airconId)
        val attempt: suspend () -> AirconState = {
            withUnit(airconId) { client ->
                val next = change(client.getStat(airconId))
                val echoed = client.setStat(airconId, next)
                next.copy(indoorTemp = echoed.indoorTemp ?: next.indoorTemp, outdoorTemp = echoed.outdoorTemp ?: next.outdoorTemp)
            }
        }
        val written = try {
            attempt()
        } catch (e: WfRacException) {
            if (e.code != WfRacException.UNIT_REFUSED) throw e
            val locked = withUnit(airconId) { it.getStatus(airconId) }.lockedForS
            if (locked > 0) {
                if (locked > waitUpToS) throw busy(airconId, locked, e.result)
                onWait(locked)
                sleep((locked + 1) * 1000L)
            } else {
                registered -= airconId
                register(airconId)
            }
            try {
                attempt()
            } catch (again: WfRacException) {
                if (again.code != WfRacException.UNIT_REFUSED) throw again
                val left = runCatching { withUnit(airconId) { it.getStatus(airconId) }.lockedForS }.getOrDefault(0)
                throw if (left > 0) busy(airconId, left, again.result) else again
            }
        }
        if (!written.power) cancelTimer(airconId)
        return written
    }

    private fun busy(airconId: String, seconds: Int, result: Int?) =
        WfRacException(WfRacException.UNIT_BUSY, "unit $airconId is in use by another client for $seconds s", result, seconds)

    /** Adds the module at [host], or updates the address of a known one. New units have no name. */
    suspend fun adopt(host: String): StoredUnit {
        requireNetwork()
        val ids = store.state.value
        val probe = WfRacClient(host, ids.deviceId, port = port, minIntervalMs = clientIntervalMs, now = now)
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

    /**
     * Looks for units: by mDNS, and by probing every address on the local network for the
     * module's port, as not every module answers mDNS reliably. Returns the number of units
     * it found.
     */
    suspend fun scan(): Int = scanMutex.withLock {
        requireNetwork()
        var found = 0
        val hosts = coroutineScope {
            val byMdns = async { announced().map { it.host } }
            val open = async { Discovery.findOpen(hostsToScan(localAddresses()), port) }
            LinkedHashSet(byMdns.await() + open.await())
        }
        for (host in hosts) {
            try {
                adopt(host)
                found++
            } catch (e: NoUnitAtAddressException) {
                // Something else listens on the module's port.
            }
        }
        lastScan = now()
        found
    }

    /**
     * Adds the demo unit, a simulated air conditioner for trying the app without one, or
     * returns it when it is there already. Scans never find it and leave it alone.
     */
    suspend fun addDemo(name: String): StoredUnit {
        store.update { s ->
            if (s.units.any { it.airconId == DEMO_AIRCON_ID }) s
            else s.copy(units = s.units + StoredUnit(DEMO_AIRCON_ID, name, host = DEMO_HOST, demo = true))
        }
        return require(DEMO_AIRCON_ID)
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

    /** Forgets a unit. Its account stays: it is shared with every other AirLAN client of the unit. */
    suspend fun forget(airconId: String) {
        timers.cancel(airconId)
        store.update { s -> s.copy(units = s.units.filterNot { it.airconId == airconId }) }
        clients.remove(airconId)
    }

    /**
     * Moves the named units off the account AirLAN 0.1.1 and older registered per installation
     * to the shared one (see [register]), which frees a slot on every unit per phone. Units out
     * of reach are tried again on the next call; the app calls this on every refresh, and it
     * does nothing once all units are done.
     */
    suspend fun migrateAccounts() {
        if (store.state.value.legacyOperatorId == null) return
        for (unit in store.state.value.units) {
            if (unit.demo || unit.accountMigrated || unit.name == null || needsNetworkPermission(unit)) continue
            try {
                register(unit.airconId)
            } catch (e: Exception) {
                // Unreachable, or full of other apps' accounts: try again next time.
            }
        }
    }

    suspend fun setTimer(airconId: String, minutes: Int) {
        require(minutes in 1..MAX_TIMER_MINUTES) { "minutes must be between 1 and $MAX_TIMER_MINUTES" }
        val timer = OffTimer(at = now() + minutes * 60_000L)
        store.updateUnit(airconId) { it.copy(timer = timer) }
        timers.schedule(airconId, timer.dueAt)
    }

    suspend fun cancelTimer(airconId: String) {
        if (store.unit(airconId)?.timer == null) return
        timers.cancel(airconId)
        store.updateUnit(airconId) { it.copy(timer = null) }
    }

    sealed interface TimerOutcome {
        data object SwitchedOff : TimerOutcome
        data object NotDue : TimerOutcome
        data class Retrying(val attempt: Int) : TimerOutcome
        data class GaveUp(val error: Exception) : TimerOutcome
    }

    /**
     * Called when a unit's alarm goes off. Switches it off, or records the failure and schedules
     * a retry (see [timerRetryDelayMs]): the unit may be out of reach for a while, or another
     * client may hold its 60-second write lock, which is then waited out. The attempt count
     * lives in the stored [OffTimer], so it survives the process and [rescheduleAll]; a retry
     * that would come later than [TIMER_GIVE_UP_MS] after the switch-off time is not made, and
     * the timer is given up.
     *
     * An alarm can go off more than once for the same attempt (see [rescheduleAll]), so firings
     * for one unit take turns, and each one works from the stored timer as it finds it.
     */
    suspend fun fireTimer(airconId: String): TimerOutcome = firing.computeIfAbsent(airconId) { Mutex() }.withLock {
        val timer = store.unit(airconId)?.timer ?: return@withLock TimerOutcome.NotDue
        if (timer.dueAt > now() + 1000) {
            timers.schedule(airconId, timer.dueAt) // Rescheduled since this alarm was set.
            return@withLock TimerOutcome.NotDue
        }
        try {
            // Runs inside a broadcast receiver, which Android gives about a minute. An
            // unreachable unit costs a timeout per transport plus a rescan, so bound the attempt.
            // Switching off clears the timer (see apply).
            withTimeout(TIMER_ATTEMPT_TIMEOUT_MS) { apply(airconId, waitUpToS = 0) { it.copy(power = false) } }
            TimerOutcome.SwitchedOff
        } catch (e: Exception) {
            val failed = timer.failedAttempts + 1
            val lockLapsesIn = (e as? WfRacException)?.retryAfterS?.let { (it + 2) * 1000L } ?: 0L
            val retryAt = now() + maxOf(timerRetryDelayMs(failed), lockLapsesIn)
            val next = if (retryAt <= timer.at + TIMER_GIVE_UP_MS) timer.copy(failedAttempts = failed, retryAt = retryAt) else null
            // The user may have cancelled or replaced the timer meanwhile; leave theirs alone.
            var replaced = false
            store.updateUnit(airconId) { u ->
                if (u.timer != timer) return@updateUnit u
                replaced = true
                u.copy(timer = next)
            }
            when {
                !replaced -> TimerOutcome.NotDue
                next != null -> {
                    timers.schedule(airconId, next.dueAt)
                    TimerOutcome.Retrying(failed)
                }
                else -> {
                    timers.cancel(airconId)
                    TimerOutcome.GaveUp(e)
                }
            }
        }
    }

    /**
     * Sets every unit's alarm from its stored timer. Android drops alarms on a reboot, an update
     * and, without telling the app, when the user revokes the exact-alarm permission; so this
     * runs whenever the process starts. Overdue ones fire right away; a pending retry keeps its
     * attempt count.
     */
    fun rescheduleAll() {
        for (u in store.state.value.units) u.timer?.let { timers.schedule(u.airconId, it.dueAt) }
    }

    companion object {
        const val MAX_TIMER_MINUTES = 6 * 60
        const val TIMER_ATTEMPT_TIMEOUT_MS = 40_000L

        /**
         * How long after the switch-off time the timer keeps trying. The module drops off Wi-Fi
         * about once an hour and sometimes stays away for up to an hour after that; roaming
         * between access points can cost 15-35 minutes. Same rule as the server (timer-policy.js).
         */
        const val TIMER_GIVE_UP_MS = 75 * 60_000L

        /** The wait before the next attempt after [failed] failed attempts (1, 2, ...): 30 s, 1, 2, 4, then 5 min. */
        fun timerRetryDelayMs(failed: Int): Long = minOf(5 * 60_000L, 30_000L shl (failed - 1).coerceIn(0, 4))

        /** A user's write waits this long for another client's write lock to lapse. */
        const val WRITE_WAIT_S = 20

        /** Real units are identified by their MAC address, so this cannot collide with one. */
        const val DEMO_AIRCON_ID = "demo"
        private const val DEMO_HOST = "demo"

        fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 } &&
                runCatching { InetAddress.getByName(s) }.isSuccess
        }
    }
}
