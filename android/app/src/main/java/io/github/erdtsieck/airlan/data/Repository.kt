package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.discovery.Announced
import io.github.erdtsieck.airlan.discovery.Discovery
import io.github.erdtsieck.airlan.discovery.LocalAddress
import io.github.erdtsieck.airlan.discovery.contains
import io.github.erdtsieck.airlan.discovery.hostsToScan
import io.github.erdtsieck.airlan.wfrac.AirconClient
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.DemoClient
import io.github.erdtsieck.airlan.wfrac.RequestTrace
import io.github.erdtsieck.airlan.wfrac.UnitUnreachableException
import io.github.erdtsieck.airlan.wfrac.WFRAC_PORT
import io.github.erdtsieck.airlan.wfrac.WfRacClient
import io.github.erdtsieck.airlan.wfrac.WfRacException
import java.io.IOException
import java.net.InetAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Nothing answers as a WF-RAC module at the address the user typed. */
class NoUnitAtAddressException(host: String) : Exception("no WF-RAC module answers at $host")

/** The user has not allowed the app on the local network (Android 17+), so real units are out of reach. */
class LocalNetworkNotAllowedException : Exception("the local network permission is not granted")

/**
 * Everything the app does with units: discovery, reading, writing, the timer and the schedule.
 * Shared by the UI and the timer receiver, so there is one client (and one request queue)
 * per unit in the process.
 */
class Repository(
    private val store: UnitStore,
    private val timers: TimerScheduler,
    private val localAddresses: () -> List<LocalAddress>,
    private val port: Int = WFRAC_PORT,
    private val clientIntervalMs: Long = 1100,
    /** How long a connected unit gets to answer; see [WfRacClient]. */
    private val clientReadTimeoutMs: Int = 10_000,
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

    /** Per unit, kept across clients (a unit that moved gets a new one) for [diagnostics]. */
    private val traces = ConcurrentHashMap<String, RequestTrace>()
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
        return WfRacClient(unit.host, ids.deviceId, unit.scheme, unit.certificatePin, port, clientIntervalMs, readTimeoutMs = clientReadTimeoutMs, now = now, trace = traceOf(unit.airconId))
    }

    private fun traceOf(airconId: String) = traces.getOrPut(airconId) { RequestTrace() }

    private fun require(airconId: String) = store.unit(airconId) ?: throw IllegalArgumentException("unknown unit $airconId")

    /**
     * Runs [block] against a unit. When nothing answers at its address at all, looks for the
     * unit (DHCP may have moved it) and retries if it moved. A unit that accepts the connection
     * but answers slowly or not at all is still there: searching would only add traffic.
     */
    private suspend fun <T> withUnit(airconId: String, block: suspend (AirconClient) -> T): T {
        val unit = require(airconId)
        if (needsNetworkPermission(unit)) throw LocalNetworkNotAllowedException()
        val client = clientFor(unit)
        try {
            return block(client)
        } catch (e: UnitUnreachableException) {
            if (unit.demo || !relocate(unit)) throw e
            return block(clientFor(require(airconId)))
        } finally {
            persistTransport(airconId, client)
        }
    }

    /**
     * Looks for a unit that stopped answering, in case DHCP gave it another address: asks mDNS
     * first, which names the unit directly, and falls back to a full scan (at most once a
     * minute). The scan is only made on the unit's own network: on any other (at work, on a
     * guest network) the unit is not there to be found, and sweeping someone else's network
     * would only look like an attack. Returns whether the unit is now known at a different address.
     */
    private suspend fun relocate(unit: StoredUnit): Boolean {
        val found = announced().firstOrNull { it.airconId == unit.airconId }
        when {
            found != null && found.host != unit.host -> runCatching { adopt(found.host) }
            found == null && !onUnitNetwork(unit) -> traceOf(unit.airconId).note("not found by mDNS, and not on its network: not searching", now())
            found == null && now() - lastScan >= 60_000 -> {
                traceOf(unit.airconId).note("not found by mDNS: searching the network", now())
                scan(verifyKnown = false)
            }
        }
        val host = store.unit(unit.airconId)?.host
        return host != null && host != unit.host
    }

    /**
     * Whether the phone is on [unit]'s network: a Wi-Fi or Ethernet network its address lies
     * in. Away from home this is false, and so it is for a unit behind a router on another
     * network of the same home; it decides what to search and how a failure is told, never
     * whether the unit is contacted.
     */
    fun onUnitNetwork(unit: StoredUnit) = unit.demo || localAddresses().any { it.contains(unit.host) }

    private suspend fun persistTransport(airconId: String, client: AirconClient) {
        val unit = store.unit(airconId) ?: return
        val scheme = client.scheme ?: return
        if (unit.scheme != scheme || unit.certificatePin != client.certificatePin) {
            store.updateUnit(airconId) { it.copy(scheme = scheme, certificatePin = client.certificatePin) }
        }
    }

    /** A report about [airconId] to share with the developer; see [Diagnostics]. */
    fun diagnostics(airconId: String, appVersion: String, androidVersion: String, state: AirconState?): String =
        Diagnostics.report(appVersion, androidVersion, require(airconId), state, traces[airconId], localAddresses(), zone(), now())

    suspend fun state(airconId: String): AirconState =
        withUnit(airconId) { it.getStat(airconId) }.also { noteAirflowSupport(airconId, it) }

    /** Remembers the model group, and that the unit reports its left/right vane once it has shown so. */
    private suspend fun noteAirflowSupport(airconId: String, s: AirconState) {
        val unit = store.unit(airconId) ?: return
        if (unit.modelNrRaw == s.modelNrRaw && (unit.reportsVaneLR || !s.vaneLRReported)) return
        store.updateUnit(airconId) { it.copy(modelNrRaw = s.modelNrRaw, reportsVaneLR = it.reportsVaneLR || s.vaneLRReported) }
    }

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
     * The user changes the unit: [write], after which a switch-off timer ends with the run it
     * belonged to, and a schedule switch that is still being retried is dropped: what the user
     * does now comes before a switch that should have happened earlier.
     */
    suspend fun apply(
        airconId: String,
        waitUpToS: Int = WRITE_WAIT_S,
        onWait: (seconds: Int) -> Unit = {},
        change: (AirconState) -> AirconState,
    ): AirconState {
        val written = write(airconId, waitUpToS, onWait, change)
        if (!written.power) cancelOffTimer(airconId)
        dropLateSwitch(airconId)
        return written
    }

    /**
     * Applies [change] to the unit's current state and writes it. On a refusal it finds out why:
     *  - another client holds the write lock: waits for it to lapse when that takes at most
     *    [waitUpToS] (telling [onWait] how long) and tries once more, or throws
     *    [WfRacException.UNIT_BUSY];
     *  - otherwise our account may be gone (deleted from elsewhere): registers again, which
     *    throws [WfRacException.NO_ACCOUNT_SLOT] when the unit is full, and tries once more.
     */
    private suspend fun write(
        airconId: String,
        waitUpToS: Int,
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
        noteAirflowSupport(airconId, written)
        return written
    }

    private fun busy(airconId: String, seconds: Int, result: Int?) =
        WfRacException(WfRacException.UNIT_BUSY, "unit $airconId is in use by another client for $seconds s", result, seconds)

    /** Adds the module at [host], or updates the address of a known one. New units have no name. */
    suspend fun adopt(host: String): StoredUnit {
        requireNetwork()
        val ids = store.state.value
        val probe = WfRacClient(host, ids.deviceId, port = port, minIntervalMs = clientIntervalMs, readTimeoutMs = clientReadTimeoutMs, now = now)
        val airconId = try {
            probe.getDeviceInfo()
        } catch (e: IOException) {
            throw NoUnitAtAddressException(host)
        } catch (e: WfRacException) {
            throw NoUnitAtAddressException(host)
        }
        val before = store.unit(airconId)
        store.update { s ->
            val existing = s.units.firstOrNull { it.airconId == airconId }
            val adopted = (existing ?: StoredUnit(airconId, name = null, host = host))
                .copy(host = host, scheme = probe.scheme, certificatePin = probe.certificatePin ?: existing?.certificatePin)
            s.copy(units = if (existing == null) s.units + adopted else s.units.map { if (it.airconId == airconId) adopted else it })
        }
        // The unit's client (its one request queue) stays unless it must reach the unit differently.
        if (before != null && (before.host != host || before.scheme != probe.scheme)) {
            clients.remove(airconId)
            if (before.host != host) traceOf(airconId).note("address changed from ${before.host} to $host", now())
        }
        return require(airconId)
    }

    /**
     * Looks for units: by mDNS, and by probing every address on the local network for the
     * module's port, as not every module answers mDNS reliably.
     *
     * The addresses of known units are left out of the probe and are not adopted again: a
     * module takes one connection at a time and wants a second between requests, and a probe
     * or a second client would cut across the requests its own client is making. A known unit
     * that moved shows up at an address that is not known, or by mDNS under its own name.
     * With [verifyKnown], each known unit is also asked for its state through its own client.
     * Returns the number of units it found (or confirmed).
     */
    suspend fun scan(verifyKnown: Boolean = true): Int = scanMutex.withLock {
        requireNetwork()
        val known = store.state.value.units.filterNot { it.demo }
        val knownAt = known.associate { it.host to it.airconId }
        var found = 0
        val hosts = coroutineScope {
            val byMdns = async { announced().filterNot { knownAt[it.host] == it.airconId }.map { it.host } }
            val open = async { Discovery.findOpen(hostsToScan(localAddresses()).filterNot { it in knownAt }, port) }
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
        if (verifyKnown) {
            for (unit in known) {
                if (store.unit(unit.airconId)?.host != unit.host) continue // Moved, and counted above.
                if (runCatching { clientFor(unit).getStat(unit.airconId) }.isSuccess) found++
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

    /** Moves a unit [by] places in the list (negative: towards the start); the tabs follow this order. */
    suspend fun move(airconId: String, by: Int) {
        store.update { s -> s.copy(units = moved(s.units, airconId, by)) }
    }

    /** Forgets a unit. Its account stays: it is shared with every other AirLAN client of the unit. */
    suspend fun forget(airconId: String) {
        for (alarm in Alarm.entries) timers.cancel(airconId, alarm)
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

    private fun zone(): ZoneId = ZoneId.of(timezone())

    // ---------- the switch-off timer ----------

    /** Switches the unit off [minutes] from now. */
    suspend fun setOffTimer(airconId: String, minutes: Int) {
        require(minutes in 1..MAX_TIMER_MINUTES) { "minutes must be between 1 and $MAX_TIMER_MINUTES" }
        val timer = UnitTimer(now() + minutes * 60_000L)
        store.updateUnit(airconId) { it.copy(offTimer = timer) }
        timers.schedule(airconId, Alarm.OFF_TIMER, timer.dueAt)
    }

    suspend fun cancelOffTimer(airconId: String) {
        if (store.unit(airconId)?.offTimer == null) return
        timers.cancel(airconId, Alarm.OFF_TIMER)
        store.updateUnit(airconId) { it.copy(offTimer = null) }
    }

    sealed interface TimerOutcome {
        data object Switched : TimerOutcome
        data object NotDue : TimerOutcome
        data class Retrying(val attempt: Int) : TimerOutcome
        /** It did not work although the phone was on the unit's network: worth telling the user. */
        data class GaveUp(val error: Exception?) : TimerOutcome
        /** The phone was never on the unit's network while it could have worked: nothing to tell. */
        data object Missed : TimerOutcome
    }

    /** Firings for one unit take turns: an alarm can go off more than once for the same attempt (see [rescheduleAll]). */
    private suspend fun <T> firingFor(airconId: String, block: suspend () -> T): T = firing.computeIfAbsent(airconId) { Mutex() }.withLock { block() }

    /** The next attempt after [failed] failed attempts, later if another client's write lock lapses later. */
    private fun retryAt(failed: Int, error: Exception?): Long {
        val lockLapsesIn = (error as? WfRacException)?.retryAfterS?.let { (it + 2) * 1000L } ?: 0L
        return now() + maxOf(timerRetryDelayMs(failed), lockLapsesIn)
    }

    /**
     * Called when a unit's switch-off alarm goes off. Writes the power, or records the failure
     * and schedules a retry (see [timerRetryDelayMs]): the unit may be out of reach for a while,
     * or another client may hold its 60-second write lock, which is then waited out. The attempt
     * count lives in the stored [UnitTimer], so it survives the process and [rescheduleAll]; a
     * retry that would come later than [TIMER_GIVE_UP_MS] after the timer's time is not made,
     * and the timer is given up.
     */
    suspend fun fireOffTimer(airconId: String): TimerOutcome = firingFor(airconId) {
        val timer = store.unit(airconId)?.offTimer ?: return@firingFor TimerOutcome.NotDue
        if (timer.dueAt > now() + 1000) {
            timers.schedule(airconId, Alarm.OFF_TIMER, timer.dueAt) // Rescheduled since this alarm was set.
            return@firingFor TimerOutcome.NotDue
        }

        // Replaces the timer by [next], unless the user cancelled or replaced it meanwhile:
        // theirs is left alone. Returns whether it was still this one.
        suspend fun settle(next: UnitTimer?): Boolean {
            var same = false
            store.updateUnit(airconId) { u ->
                if (u.offTimer != timer) return@updateUnit u
                same = true
                u.copy(offTimer = next)
            }
            return same
        }

        try {
            // Runs inside a broadcast receiver, which Android gives about a minute. An
            // unreachable unit costs a timeout per transport plus a rescan, so bound the attempt.
            withTimeout(TIMER_ATTEMPT_TIMEOUT_MS) { write(airconId, waitUpToS = 0) { it.copy(power = false) } }
            settle(null)
            TimerOutcome.Switched
        } catch (e: Exception) {
            val failed = timer.failedAttempts + 1
            val retryAt = retryAt(failed, e)
            val next = if (retryAt <= timer.at + TIMER_GIVE_UP_MS) timer.copy(failedAttempts = failed, retryAt = retryAt) else null
            when {
                !settle(next) -> TimerOutcome.NotDue
                next != null -> {
                    timers.schedule(airconId, Alarm.OFF_TIMER, next.dueAt)
                    TimerOutcome.Retrying(failed)
                }
                else -> {
                    timers.cancel(airconId, Alarm.OFF_TIMER)
                    TimerOutcome.GaveUp(e)
                }
            }
        }
    }

    // ---------- the schedule ----------

    /** Adds [rule] to the unit's schedule, or replaces the rule with its id. */
    suspend fun saveRule(airconId: String, rule: ScheduleRule) = editSchedule(airconId) { s ->
        val rules = if (s.rules.any { it.id == rule.id }) s.rules.map { if (it.id == rule.id) rule else it } else s.rules + rule
        s.copy(rules = rules.sortedBy { it.minuteOfDay })
    }

    suspend fun deleteRule(airconId: String, ruleId: String) = editSchedule(airconId) { s -> s.copy(rules = s.rules.filterNot { it.id == ruleId }) }

    suspend fun enableRule(airconId: String, ruleId: String, enabled: Boolean) = editSchedule(airconId) { s ->
        s.copy(rules = s.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
    }

    /** Skips the schedule's next switch. A one-time rule is turned off instead: skipped, it has nothing left to do. */
    suspend fun skipNext(airconId: String) = editSchedule(airconId) { s ->
        val run = s.run ?: return@editSchedule s
        val rule = s.rules.firstOrNull { it.id == run.ruleId }
        if (rule?.once == true) s.copy(rules = s.rules.map { if (it.id == rule.id) it.copy(enabled = false) else it })
        else s.copy(skipAt = run.at)
    }

    suspend fun unskip(airconId: String) = editSchedule(airconId) { it.copy(skipAt = null) }

    /** No rule runs up to and including [until]; null resumes the schedule. */
    suspend fun pauseSchedule(airconId: String, until: LocalDate?) = editSchedule(airconId) { it.copy(pausedUntil = until) }

    /** A new rule's id, unique within the unit's schedule. */
    fun newRuleId(): String = UUID.randomUUID().toString().take(8)

    private suspend fun editSchedule(airconId: String, edit: (Schedule) -> Schedule) {
        store.updateUnit(airconId) { u -> u.copy(schedule = u.schedule.replanned(edit(u.schedule), now(), zone())) }
        syncScheduleAlarm(airconId)
    }

    private fun syncScheduleAlarm(airconId: String) {
        val run = store.unit(airconId)?.schedule?.run
        if (run != null) timers.schedule(airconId, Alarm.SCHEDULE, run.dueAt) else timers.cancel(airconId, Alarm.SCHEDULE)
    }

    /** See [apply]: a schedule switch still being retried gives way to what the user does. */
    private suspend fun dropLateSwitch(airconId: String) {
        if ((store.unit(airconId)?.schedule?.run?.failedAttempts ?: 0) == 0) return
        store.updateUnit(airconId) { u ->
            if (u.schedule.run?.failedAttempts == 0) u else u.copy(schedule = u.schedule.planned(now(), zone()))
        }
        syncScheduleAlarm(airconId)
    }

    /**
     * Called when a unit's schedule alarm goes off: makes the switch the rule asks for. A switch
     * that fails is tried again on the timer's schedule (see [timerRetryDelayMs]) until its
     * window closes (see [Schedule.windowEnd]); after that the next switch is planned. While
     * the phone has no Wi-Fi or Ethernet at all it is not tried, only planned again.
     *
     * The outcome tells whether the user should hear of a failure: only when the phone was on
     * the unit's network and the unit did not answer ([TimerOutcome.GaveUp]). A switch missed
     * because the phone was elsewhere ([TimerOutcome.Missed]) is only shown on the schedule.
     */
    suspend fun fireSchedule(airconId: String): TimerOutcome = firingFor(airconId) {
        val unit = store.unit(airconId) ?: return@firingFor TimerOutcome.NotDue
        val schedule = unit.schedule
        val run = schedule.run ?: return@firingFor TimerOutcome.NotDue
        if (run.dueAt > now() + 1000) {
            timers.schedule(airconId, Alarm.SCHEDULE, run.dueAt)
            return@firingFor TimerOutcome.NotDue
        }
        val zone = zone()
        val rule = schedule.rules.firstOrNull { it.id == run.ruleId && it.enabled }
        if (rule == null) {
            editSchedule(airconId) { it }
            return@firingFor TimerOutcome.NotDue
        }

        // Replaces the run by what comes after it, unless the user changed the schedule
        // meanwhile. Returns whether it was still this run.
        suspend fun settle(transform: (Schedule) -> Schedule): Boolean {
            var same = false
            store.updateUnit(airconId) { u ->
                if (u.schedule.run != run) return@updateUnit u
                same = true
                u.copy(schedule = transform(u.schedule))
            }
            syncScheduleAlarm(airconId)
            return same
        }

        // The rule is done with, switched or not: a one-time rule turns itself off, and the
        // next switch is planned from now (switches missed meanwhile are past their window).
        suspend fun finish(doneAt: Long?) = settle { s ->
            val rules = if (rule.once) s.rules.map { if (it.id == rule.id) it.copy(enabled = false) else it } else s.rules
            s.copy(rules = rules, last = ScheduleOutcome(rule.action, run.at, doneAt)).planned(maxOf(now(), run.at), zone)
        }

        val windowEnd = schedule.windowEnd(run, zone)
        val connected = unit.demo || localAddresses().isNotEmpty()
        var error: Exception? = null
        if (now() < windowEnd && connected) {
            try {
                withTimeout(TIMER_ATTEMPT_TIMEOUT_MS) { write(airconId, waitUpToS = 0, change = rule::applyTo) }
                return@firingFor if (finish(now())) TimerOutcome.Switched else TimerOutcome.NotDue
            } catch (e: Exception) {
                error = e
            }
        }
        val failedAtHome = run.failedAtHome || (error != null && onUnitNetwork(unit))
        val failed = run.failedAttempts + 1
        val retryAt = retryAt(failed, error)
        when {
            retryAt < windowEnd -> {
                val retrying = settle { it.copy(run = run.copy(failedAttempts = failed, retryAt = retryAt, failedAtHome = failedAtHome)) }
                if (retrying) TimerOutcome.Retrying(failed) else TimerOutcome.NotDue
            }
            !finish(null) -> TimerOutcome.NotDue
            failedAtHome -> TimerOutcome.GaveUp(error)
            else -> TimerOutcome.Missed
        }
    }

    /**
     * Sets every unit's alarms from its stored timer and schedule. Android drops alarms on a
     * reboot, an update and, without telling the app, when the user revokes the exact-alarm
     * permission; so this runs whenever the process starts. Overdue ones fire right away; a
     * pending retry keeps its attempt count.
     */
    fun rescheduleAll() {
        // A schedule whose next switch was never planned (a state file written by hand, or by
        // a tool) gets one now: enabled rules always have a planned switch.
        val unplanned = store.state.value.units.filter { u -> u.schedule.run == null && u.schedule.rules.any { it.enabled } }
        if (unplanned.isNotEmpty()) runBlocking {
            for (u in unplanned) store.updateUnit(u.airconId) { it.copy(schedule = it.schedule.planned(now(), zone())) }
        }
        for (u in store.state.value.units) {
            u.offTimer?.let { timers.schedule(u.airconId, Alarm.OFF_TIMER, it.dueAt) }
            u.schedule.run?.let { timers.schedule(u.airconId, Alarm.SCHEDULE, it.dueAt) }
        }
    }

    companion object {
        const val MAX_TIMER_MINUTES = 6 * 60
        const val TIMER_ATTEMPT_TIMEOUT_MS = 40_000L

        /**
         * How long after its time a timer keeps trying. The module drops off Wi-Fi about once an
         * hour and sometimes stays away for up to an hour after that; roaming between access
         * points can cost 15-35 minutes. Same rule as the server (timers.js).
         */
        const val TIMER_GIVE_UP_MS = 75 * 60_000L

        /** [units] with [airconId] moved [by] places, clamped to the ends; unchanged if it is not there. */
        fun moved(units: List<StoredUnit>, airconId: String, by: Int): List<StoredUnit> {
            val from = units.indexOfFirst { it.airconId == airconId }
            if (from < 0) return units
            val to = (from + by).coerceIn(0, units.lastIndex)
            if (to == from) return units
            return units.toMutableList().apply { add(to, removeAt(from)) }
        }

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
