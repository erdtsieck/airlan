package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Codec
import io.github.erdtsieck.airlan.wfrac.Mode
import io.github.erdtsieck.airlan.wfrac.Scheme
import java.io.File
import java.io.IOException
import java.net.InetAddress
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

    /** A fake unit over plain HTTP that keeps a state and applies writes to it. */
    private class FakeUnit(var state: AirconState, val airconId: String = "e81656cb0d45") {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** The real module does not unescape "\/" and refuses the garbled frame; see JsonWriter. */
        @Volatile var sawEscapedSlash = false
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
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
                        val contents = JSONObject().put("airconId", airconId)
                        if (command == "setAirconStat") state = Codec.decode(payload.getJSONObject("contents").getString("airconStat"))
                        if (command == "getAirconStat" || command == "setAirconStat") contents.put("airconStat", Codec.encode(state))
                        val body = JSONObject().put("result", 0).put("contents", contents).toString()
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

    private fun repository(port: Int = unit.port) = Repository(
        UnitStore(File(dir, "state.json")), timers, { emptyList() }, port,
        clientIntervalMs = 0, timezone = { "Europe/Amsterdam" }, now = { clock }, localNetworkAllowed = { networkAllowed },
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
    fun `an unreachable unit is retried every 30 seconds, then given up`() = runBlocking {
        val at = dueTimerOnUnreachableUnit()
        val repo = repository()
        for (n in 1 until Repository.TIMER_ATTEMPTS) {
            assertEquals(Repository.TimerOutcome.Retrying(n), repo.fireTimer(ID))
            assertEquals(ID to clock + 30_000L, timers.scheduled.last())
            assertEquals(OffTimer(at, failedAttempts = n, retryAt = clock + 30_000L), storedTimer())
            clock += 30_000L
        }
        val scheduled = timers.scheduled.size

        assertTrue(repo.fireTimer(ID) is Repository.TimerOutcome.GaveUp)
        assertNull(storedTimer())
        assertEquals(scheduled, timers.scheduled.size)
        assertEquals(listOf(ID), timers.cancelled)
        assertEquals(Repository.TimerOutcome.NotDue, repo.fireTimer(ID))
    }

    @Test
    fun `the retry count survives a restart`() = runBlocking {
        val at = dueTimerOnUnreachableUnit()
        assertEquals(Repository.TimerOutcome.Retrying(1), repository().fireTimer(ID))
        clock += 30_000L

        assertEquals(Repository.TimerOutcome.Retrying(2), repository().fireTimer(ID))
        assertEquals(OffTimer(at, failedAttempts = 2, retryAt = clock + 30_000L), storedTimer())
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
        dueTimerOnUnreachableUnit()
        val outcomes = (1..Repository.TIMER_ATTEMPTS).map {
            val repo = repository()
            repo.rescheduleAll()
            repo.fireTimer(ID).also { clock += 30_000L }
        }
        assertEquals((1 until Repository.TIMER_ATTEMPTS).map { Repository.TimerOutcome.Retrying(it) }, outcomes.dropLast(1))
        assertTrue(outcomes.last() is Repository.TimerOutcome.GaveUp)
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

    @Test(expected = NoUnitAtAddressException::class)
    fun `adding an address where no unit answers fails clearly`(): Unit = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        repository(port = closedPort).add("127.0.0.1")
    }
}
