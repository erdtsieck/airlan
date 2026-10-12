package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.discovery.Announced
import io.github.erdtsieck.airlan.wfrac.AIRLAN_OPERATOR_ID
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.WfRacException
import io.github.erdtsieck.airlan.wfrac.Codec
import io.github.erdtsieck.airlan.wfrac.Mode
import io.github.erdtsieck.airlan.wfrac.Scheme
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ID = "e81656cb0d45"

class RepositoryTest {
    private val fixtures = File(System.getProperty("airlan.fixtures") ?: error("airlan.fixtures not set"))
    private val liveFrame = JSONObject(File(fixtures, "live-frames.json").readText())
        .getJSONObject("frames").getJSONObject("e81656cb0d45").getString("airconStat")

    /**
     * A fake unit over plain HTTP that keeps a state and applies writes to it. Like the real
     * module it holds four account slots (refusing a new account when they are full), refuses
     * writes from unregistered operators (result 2), and gives the last writer 60 seconds of
     * exclusive write access, keyed on deviceId and timed by the requests' timestamps.
     */
    private class FakeUnit(var state: AirconState, val airconId: String = "e81656cb0d45", port: Int = 0) {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val accounts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        @Volatile var lockWriter: String? = null
        @Volatile var lockExpires = 0L
        @Volatile var clock = 0L

        /** Another app writes, and holds the lock for a minute from the unit's current time. */
        fun otherWrite(lockSeconds: Long = 60) {
            lockWriter = "other-app"
            lockExpires = clock + lockSeconds
        }

        /** The real module does not unescape "\/" and refuses the garbled frame; see JsonWriter. */
        @Volatile var sawEscapedSlash = false
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50)
        }
        val port get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (e: IOException) { break }
                    s.use {
                        val buf = ByteArray(8192)
                        val n = it.getInputStream().read(buf)
                        val raw = String(buf, 0, n).substringAfter("\r\n\r\n")
                        if ("\\/" in raw) sawEscapedSlash = true
                        val payload = JSONObject(raw)
                        val command = payload.getString("command")
                        commands += command
                        clock = payload.getLong("timestamp")
                        val operator = payload.getString("operatorId")
                        val device = payload.getString("deviceId")
                        val request = payload.optJSONObject("contents")
                        val contents = JSONObject().put("airconId", airconId)
                        val result = when (command) {
                            "updateAccountInfo" -> {
                                val id = request!!.getString("accountId")
                                if (id in accounts || accounts.size < 4) { accounts += id; 0 } else 1
                            }
                            "deleteAccountInfo" -> if (accounts.remove(request!!.getString("accountId"))) 0 else 1
                            "setAirconStat" -> when {
                                operator !in accounts -> 2
                                lockWriter != null && lockExpires >= clock && lockWriter != device -> 1
                                else -> {
                                    state = Codec.decode(request!!.getString("airconStat"))
                                    lockWriter = device
                                    lockExpires = clock + 60
                                    0
                                }
                            }
                            else -> 0
                        }
                        if (command == "getAirconStat" || command == "setAirconStat") {
                            contents.put("airconStat", Codec.encode(state)).put("expires", lockExpires).put("numOfAccount", accounts.size)
                        }
                        val body = JSONObject().put("result", result).put("contents", contents).toString()
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray())
                    }
                }
            }
        }

        fun close() = server.close()
    }

    private class RecordingTimers : TimerScheduler {
        val scheduled = mutableListOf<Pair<String, Long>>()
        val cancelled = mutableListOf<String>()
        override fun schedule(airconId: String, atMillis: Long) { scheduled += airconId to atMillis }
        override fun cancel(airconId: String) { cancelled += airconId }
    }

    private val dir = Files.createTempDirectory("airlan").toFile()
    private val unit = FakeUnit(Codec.decode(liveFrame))
    private val timers = RecordingTimers()
    private var clock = 1_000_000L

    private var networkAllowed = true
    private var announced = emptyList<Announced>()
    private val slept = mutableListOf<Long>()

    private fun repository(port: Int = unit.port) = Repository(
        UnitStore(File(dir, "state.json")), timers, { emptyList() }, port,
        clientIntervalMs = 0, timezone = { "Europe/Amsterdam" }, now = { clock }, localNetworkAllowed = { networkAllowed },
        announced = { announced },
        sleep = { ms -> slept += ms; clock += ms },
    )

    @After
    fun cleanUp() {
        unit.close()
        dir.deleteRecursively()
    }

    @Test
    fun `adopting a unit stores it unnamed with its transport, and survives a restart`() = runBlocking {
        val added = repository().add(" 127.0.0.1 ")
        assertNull(added.name)
        assertEquals(Scheme.HTTP, added.scheme)
        assertEquals(listOf(added), repository().units.value.units)
    }

    @Test
    fun `moving a unit reorders the list, clamped at the ends, and survives a restart`() = runBlocking {
        val repo = repository()
        repo.addDemo("Demo")
        repo.add("127.0.0.1")
        val real = "e81656cb0d45"
        fun order() = repository().units.value.units.map { it.airconId }

        repo.move(real, -1)
        assertEquals(listOf(real, Repository.DEMO_AIRCON_ID), order())
        repo.move(real, -5)
        assertEquals(listOf(real, Repository.DEMO_AIRCON_ID), order())
        repo.move(real, 1)
        assertEquals(listOf(Repository.DEMO_AIRCON_ID, real), order())
        repo.move("unknown", 1)
        assertEquals(listOf(Repository.DEMO_AIRCON_ID, real), order())
    }

    @Test
    fun `moved keeps every unit once`() {
        val units = listOf("a", "b", "c", "d").map { StoredUnit(it, null, "h") }
        fun ids(l: List<StoredUnit>) = l.joinToString("") { it.airconId }
        assertEquals("cabd", ids(Repository.moved(units, "c", -2)))
        assertEquals("bcda", ids(Repository.moved(units, "a", 9)))
        assertEquals("abcd", ids(Repository.moved(units, "x", 1)))
    }

    @Test
    fun `a unit's MAC label is its airconId as printed, anything else as is`() {
        assertEquals("E8:16:56:C0:9E:49", StoredUnit("e81656c09e49", null, "h").macLabel)
        assertEquals("demo", StoredUnit("demo", null, "h").macLabel)
    }

    @Test
    fun `a write registers once and applies only the change`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.apply("e81656cb0d45") { it.copy(power = true, mode = Mode.COOL) }
        repo.apply("e81656cb0d45") { it.copy(presetTemp = 22.5) }

        assertEquals(1, unit.commands.count { it == "updateAccountInfo" })
        assertTrue(unit.state.power)
        assertEquals(Mode.COOL, unit.state.mode)
        assertEquals(22.5, unit.state.presetTemp, 0.0)
        assertEquals(Codec.decode(liveFrame).windLR, unit.state.windLR)
        assertFalse("request bodies must not escape slashes", unit.sawEscapedSlash)
    }

    @Test
    fun `fan speed and the up-down vane are written, the rest of the state kept`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.apply("e81656cb0d45") { it.copy(power = true, presetTemp = 22.0) }
        for ((airFlow, windUD) in listOf(3 to 0, 0 to 2, 4 to 4, 1 to 1)) {
            repo.apply("e81656cb0d45") { it.copy(airFlow = airFlow, windUD = windUD) }
            assertEquals(listOf(airFlow, windUD), listOf(unit.state.airFlow, unit.state.windUD))
            assertTrue(unit.state.power)
            assertEquals(22.0, unit.state.presetTemp, 0.0)
        }
    }

    @Test
    fun `a due timer switches the unit off and clears itself`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.apply("e81656cb0d45") { it.copy(power = true) }
        repo.setTimer("e81656cb0d45", 30)
        assertEquals(clock + 30 * 60_000L, timers.scheduled.last().second)

        clock += 30 * 60_000L
        assertEquals(Repository.TimerOutcome.SwitchedOff, repo.fireTimer("e81656cb0d45"))
        assertFalse(unit.state.power)
        assertNull(repository().units.value.units.single().timer)
    }

    @Test
    fun `an early alarm is put back instead of switching off`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.setTimer("e81656cb0d45", 60)
        assertEquals(Repository.TimerOutcome.NotDue, repo.fireTimer("e81656cb0d45"))
        assertEquals(2, timers.scheduled.size)
    }

    /** A one-minute timer on the fake unit, now due, with the unit gone. Returns when it was due. */
    private suspend fun dueTimerOnUnreachableUnit(): Long {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.setTimer(ID, 1)
        unit.close()
        clock += 60_000L
        return clock
    }

    private fun storedTimer() = repository().units.value.units.single().timer

    @Test
    fun `an unreachable unit is retried ever less often until 75 minutes after the switch-off time`() = runBlocking {
        val at = dueTimerOnUnreachableUnit()
        val repo = repository()
        val delays = mutableListOf<Long>()
        while (true) {
            val outcome = repo.fireTimer(ID)
            if (outcome !is Repository.TimerOutcome.Retrying) {
                assertTrue(outcome is Repository.TimerOutcome.GaveUp)
                break
            }
            val retryAt = timers.scheduled.last().second
            assertEquals(OffTimer(at, failedAttempts = outcome.attempt, retryAt = retryAt), storedTimer())
            delays += retryAt - clock
            clock = retryAt
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), delays.take(6))
        assertTrue("an outage of an hour is covered", clock - at >= 70 * 60_000L)
        assertTrue(clock - at <= Repository.TIMER_GIVE_UP_MS)
        assertNull(storedTimer())
        assertEquals(listOf(ID), timers.cancelled)
        assertEquals(Repository.TimerOutcome.NotDue, repo.fireTimer(ID))
    }

    @Test
    fun `the retry count survives a restart`() = runBlocking {
        val at = dueTimerOnUnreachableUnit()
        assertEquals(Repository.TimerOutcome.Retrying(1), repository().fireTimer(ID))
        clock += 30_000L

        assertEquals(Repository.TimerOutcome.Retrying(2), repository().fireTimer(ID))
        assertEquals(OffTimer(at, failedAttempts = 2, retryAt = clock + 60_000L), storedTimer())
        val json = JSONObject(File(dir, "state.json").readText()).getJSONArray("units").getJSONObject(0)
        assertEquals(2, json.getInt("offFailedAttempts"))
        assertEquals(at, json.getLong("offAt"))
    }

    @Test
    fun `rescheduling after a restart keeps a pending retry and its count`() = runBlocking {
        val at = dueTimerOnUnreachableUnit()
        repository().fireTimer(ID)
        val retryAt = clock + 30_000L
        timers.scheduled.clear()

        val restarted = repository()
        restarted.rescheduleAll()
        assertEquals(listOf(ID to retryAt), timers.scheduled)
        assertEquals(OffTimer(at, failedAttempts = 1, retryAt = retryAt), storedTimer())

        // The alarm rescheduleAll set can go off along with the retry alarm; only one attempt counts.
        assertEquals(Repository.TimerOutcome.NotDue, restarted.fireTimer(ID))
        clock = retryAt
        assertEquals(Repository.TimerOutcome.Retrying(2), restarted.fireTimer(ID))
    }

    @Test
    fun `restarting before every attempt still gives up after the limit`() = runBlocking {
        val at = dueTimerOnUnreachableUnit()
        val outcomes = mutableListOf<Repository.TimerOutcome>()
        while (outcomes.lastOrNull().let { it == null || it is Repository.TimerOutcome.Retrying }) {
            val repo = repository()
            repo.rescheduleAll()
            outcomes += repo.fireTimer(ID)
            storedTimer()?.let { clock = it.dueAt }
        }
        assertEquals((1 until outcomes.size).map { Repository.TimerOutcome.Retrying(it) }, outcomes.dropLast(1))
        assertTrue(outcomes.last() is Repository.TimerOutcome.GaveUp)
        assertTrue(clock - at <= Repository.TIMER_GIVE_UP_MS)
        assertNull(storedTimer())
        timers.scheduled.clear()
        repository().rescheduleAll()
        assertTrue(timers.scheduled.isEmpty())
    }

    @Test
    fun `a timer stored before retries were persisted loads as not yet attempted`() {
        File(dir, "state.json").writeText(
            """{"deviceId": "airlan-1", "operatorId": "op", "units": [{"airconId": "$ID", "name": "Woonkamer", "host": "127.0.0.1", "offAt": 1234}]}""",
        )
        assertEquals(OffTimer(1234), storedTimer())
        repository().rescheduleAll()
        assertEquals(listOf(ID to 1234L), timers.scheduled)
    }

    @Test
    fun `switching off by hand cancels the timer`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.setTimer("e81656cb0d45", 120)
        repo.apply("e81656cb0d45") { it.copy(power = false) }
        assertEquals(listOf("e81656cb0d45"), timers.cancelled)
        assertNull(repo.units.value.units.single().timer)
    }

    @Test
    fun `naming, forgetting and address validation`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.rename("e81656cb0d45", "  Woonkamer ")
        assertEquals("Woonkamer", repo.units.value.units.single().name)
        repo.forget("e81656cb0d45")
        assertTrue(repo.units.value.units.isEmpty())

        for (bad in listOf("", "256.1.1.1", "1.2.3", "example.com", "1.2.3.4.5")) assertFalse(bad, Repository.isIpv4(bad))
    }

    @Test
    fun `the demo unit is stored with its marker and survives a restart`() = runBlocking {
        val demo = repository().addDemo("Demo unit")
        assertEquals(StoredUnit(Repository.DEMO_AIRCON_ID, "Demo unit", host = demo.host, demo = true), demo)
        assertEquals(demo, repository().addDemo("Another name"))
        assertEquals(listOf(demo), repository().units.value.units)
        assertEquals(true, JSONObject(File(dir, "state.json").readText()).getJSONArray("units").getJSONObject(0).getBoolean("demo"))
    }

    @Test
    fun `state files from before the demo unit load as real units`() {
        File(dir, "state.json").writeText(
            """{"deviceId": "airlan-1", "operatorId": "op", "units": [{"airconId": "e81656cb0d45", "name": "Woonkamer", "host": "127.0.0.1", "scheme": "HTTP"}]}""",
        )
        assertEquals(StoredUnit("e81656cb0d45", "Woonkamer", "127.0.0.1", Scheme.HTTP), repository().units.value.units.single())
    }

    @Test
    fun `the demo unit is operated like a real one, timer included`() = runBlocking {
        val repo = repository()
        val id = repo.addDemo("Demo unit").airconId
        val on = repo.apply(id) { it.copy(power = true, mode = Mode.HEAT, presetTemp = 24.0) }
        assertTrue(on.power)
        assertEquals(on, repo.state(id))

        repo.setTimer(id, 30)
        assertEquals(id to clock + 30 * 60_000L, timers.scheduled.last())
        clock += 30 * 60_000L
        assertEquals(Repository.TimerOutcome.SwitchedOff, repo.fireTimer(id))
        assertFalse(repo.state(id).power)
        assertEquals(Mode.HEAT, repo.state(id).mode)
        assertNull(repo.units.value.units.single().timer)
    }

    @Test
    fun `scans never find the demo unit and leave it alone`() = runBlocking {
        val repo = repository()
        val demo = repo.addDemo("Demo unit")
        repo.add("127.0.0.1")
        assertEquals(0, repo.scan())
        assertEquals(listOf(demo.airconId, "e81656cb0d45"), repo.units.value.units.map { it.airconId })
        assertEquals(demo, repo.units.value.units.first())
        assertFalse(unit.commands.isEmpty())
    }

    @Test
    fun `forgetting the demo unit removes it and its timer, and a new one starts afresh`() = runBlocking {
        val repo = repository()
        val id = repo.addDemo("Demo unit").airconId
        repo.apply(id) { it.copy(power = true) }
        repo.setTimer(id, 60)
        repo.forget(id)
        assertTrue(repo.units.value.units.isEmpty())
        assertEquals(listOf(id), timers.cancelled)

        repo.addDemo("Demo unit")
        assertFalse(repo.state(id).power)
    }

    @Test
    fun `without the local network permission real units are left alone and the demo works`() = runBlocking {
        repository().add("127.0.0.1")
        unit.commands.clear()
        networkAllowed = false
        val repo = repository()

        assertTrue(repo.needsNetworkPermission(repo.units.value.units.single()))
        for (action in listOf<suspend () -> Unit>(
            { repo.state(ID) },
            { repo.apply(ID) { it.copy(power = true) } },
            { repo.scan() },
            { repo.add("127.0.0.1") },
        )) {
            assertTrue(runCatching { action() }.exceptionOrNull() is LocalNetworkNotAllowedException)
        }
        assertEquals(emptyList<String>(), unit.commands)

        val demo = repo.addDemo("Demo unit")
        assertFalse(repo.needsNetworkPermission(demo))
        assertTrue(repo.apply(demo.airconId) { it.copy(power = true) }.power)

        networkAllowed = true
        assertFalse(repo.needsNetworkPermission(repo.units.value.units.first()))
        repo.state(ID)
        assertEquals(listOf("getAirconStat"), unit.commands)
    }

    private fun code(e: Throwable?) = (e as? WfRacException)?.code

    @Test
    fun `a unit without a free account slot says so when claimed and when written`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        unit.accounts += listOf("a", "b", "c", "d")
        assertEquals(WfRacException.NO_ACCOUNT_SLOT, code(runCatching { repo.claim(ID) }.exceptionOrNull()))
        assertEquals(WfRacException.NO_ACCOUNT_SLOT, code(runCatching { repo.apply(ID) { it.copy(power = true) } }.exceptionOrNull()))
        assertFalse(unit.state.power)
    }

    @Test
    fun `claiming registers, and a claim that cannot reach the unit is left for the first write`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.claim(ID)
        assertEquals(setOf("updateAccountInfo"), unit.commands.filter { it == "updateAccountInfo" }.toSet())
        unit.close()
        repository().claim(ID) // no exception
    }

    @Test
    fun `another client holding the write lock is unit_busy, with the seconds it has left`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.claim(ID)
        unit.otherWrite()
        val e = runCatching { repo.apply(ID) { it.copy(power = true) } }.exceptionOrNull() as WfRacException
        assertEquals(WfRacException.UNIT_BUSY, e.code)
        assertTrue("${e.retryAfterS}", e.retryAfterS!! in 55..60)
        assertFalse(unit.state.power)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `a write lock that lapses soon is waited out, and the write goes through`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.claim(ID)
        unit.clock = clock / 1000
        unit.otherWrite(lockSeconds = 5)
        val waited = mutableListOf<Int>()
        assertTrue(repo.apply(ID, onWait = { waited += it }) { it.copy(power = true) }.power)
        assertEquals(listOf(5), waited)
        assertEquals(listOf(6_000L), slept)
        assertTrue(unit.state.power)
    }

    @Test
    fun `our own write lock does not keep us out`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.apply(ID, waitUpToS = 0) { it.copy(power = true) }
        assertTrue(repo.apply(ID, waitUpToS = 0) { it.copy(presetTemp = 24.0) }.power)
        assertEquals(24.0, unit.state.presetTemp, 0.0)
    }

    @Test
    fun `an account removed behind our back is registered again`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.apply(ID) { it.copy(power = true) }
        unit.accounts.clear() // e.g. freed from another phone
        assertFalse(repo.apply(ID) { it.copy(power = false) }.power)
        assertEquals(2, unit.commands.count { it == "updateAccountInfo" })
    }

    @Test
    fun `a timer that meets a write lock retries once the lock has lapsed`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.apply(ID) { it.copy(power = true) }
        repo.setTimer(ID, 1)
        clock += 60_000L
        unit.clock = clock / 1000
        unit.otherWrite(lockSeconds = 50)
        assertEquals(Repository.TimerOutcome.Retrying(1), repo.fireTimer(ID))
        assertEquals(clock + 52_000L, storedTimer()!!.retryAt)
        clock = storedTimer()!!.dueAt
        assertEquals(Repository.TimerOutcome.SwitchedOff, repo.fireTimer(ID))
        assertFalse(unit.state.power)
    }

    @Test
    fun `every installation registers the one shared account, and forgetting leaves it`() = runBlocking {
        repository().add("127.0.0.1")
        repository().claim(ID)
        // A second phone: its own deviceId, the same account.
        val other = Files.createTempDirectory("airlan").toFile()
        try {
            val phone2 = Repository(
                UnitStore(File(other, "state.json")), RecordingTimers(), { emptyList() }, unit.port,
                clientIntervalMs = 0, now = { clock },
            )
            phone2.add("127.0.0.1")
            phone2.claim(ID)
        } finally {
            other.deleteRecursively()
        }
        assertEquals(setOf(AIRLAN_OPERATOR_ID), unit.accounts.toSet())
        repository().forget(ID)
        assertEquals(setOf(AIRLAN_OPERATOR_ID), unit.accounts.toSet())
        assertFalse(unit.commands.contains("deleteAccountInfo"))
    }

    /** A state file as AirLAN 0.1.1 left it: an account of its own, registered with the named unit. */
    private fun legacyInstall(name: String? = "Woonkamer") {
        File(dir, "state.json").writeText(
            JSONObject().put("deviceId", "airlan-1").put("operatorId", "legacy-op").put(
                "units",
                org.json.JSONArray().put(JSONObject().put("airconId", ID).put("name", name ?: JSONObject.NULL).put("host", "127.0.0.1").put("scheme", "HTTP")),
            ).toString(),
        )
        unit.accounts += "legacy-op"
    }

    @Test
    fun `an upgrade moves a unit from the old account to the shared one, even on a full unit`() = runBlocking {
        legacyInstall()
        unit.accounts += listOf("other-1", "other-2", "other-3")
        val repo = repository()
        repo.migrateAccounts()
        assertEquals(setOf("other-1", "other-2", "other-3", AIRLAN_OPERATOR_ID), unit.accounts.toSet())
        assertTrue(repository().units.value.units.single().accountMigrated)
        // Done once: later calls leave the unit alone.
        unit.commands.clear()
        repository().migrateAccounts()
        assertTrue(unit.commands.isEmpty())
        assertTrue(repo.apply(ID) { it.copy(power = true) }.power)
    }

    @Test
    fun `a unit out of reach is migrated when it is back`() = runBlocking {
        legacyInstall()
        val port = unit.port
        val accounts = unit.accounts.toSet()
        unit.close()
        repository().migrateAccounts()
        assertFalse(repository().units.value.units.single().accountMigrated)

        val back = FakeUnit(Codec.decode(liveFrame), port = port)
        try {
            back.accounts += accounts
            repository(port).migrateAccounts()
            assertEquals(setOf(AIRLAN_OPERATOR_ID), back.accounts.toSet())
            assertTrue(repository().units.value.units.single().accountMigrated)
        } finally {
            back.close()
        }
    }

    @Test
    fun `an old account that is gone already does not stop the migration, and unnamed units wait`() = runBlocking {
        legacyInstall(name = null)
        unit.accounts.clear()
        repository().migrateAccounts()
        assertTrue(unit.accounts.isEmpty())
        repository().rename(ID, "Woonkamer")
        repository().migrateAccounts()
        assertEquals(setOf(AIRLAN_OPERATOR_ID), unit.accounts.toSet())
    }

    @Test
    fun `a unit that moved is found again by mDNS`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repository().units.value.units // stored
        // The unit is remembered at an address where nothing answers; mDNS knows better.
        UnitStore(File(dir, "state.json")).updateUnit(ID) { it.copy(host = "127.0.0.2") }
        announced = listOf(Announced(ID, "127.0.0.1"))
        val moved = repository()
        assertEquals(Codec.decode(liveFrame).presetTemp, moved.state(ID).presetTemp, 0.0)
        assertEquals("127.0.0.1", moved.units.value.units.single().host)
    }

    @Test
    fun `a scan adopts units that announce themselves by mDNS`() = runBlocking {
        announced = listOf(Announced(ID, "127.0.0.1"))
        assertEquals(1, repository().scan())
        assertEquals(listOf(ID), repository().units.value.units.map { it.airconId })
    }

    @Test(expected = NoUnitAtAddressException::class)
    fun `adding an address where no unit answers fails clearly`(): Unit = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        repository(port = closedPort).add("127.0.0.1")
    }
}
