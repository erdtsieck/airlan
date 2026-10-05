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
        val scheduled = mutableListOf<Triple<String, Long, Int>>()
        val cancelled = mutableListOf<String>()
        override fun schedule(airconId: String, atMillis: Long, attempt: Int) { scheduled += Triple(airconId, atMillis, attempt) }
        override fun cancel(airconId: String) { cancelled += airconId }
    }

    private val dir = Files.createTempDirectory("airlan").toFile()
    private val unit = FakeUnit(Codec.decode(liveFrame))
    private val timers = RecordingTimers()
    private var clock = 1_000_000L

    private fun repository(port: Int = unit.port) =
        Repository(UnitStore(File(dir, "state.json")), timers, { emptyList() }, port, clientIntervalMs = 0, timezone = { "Europe/Amsterdam" }, now = { clock })

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
        assertEquals(Repository.TimerOutcome.SwitchedOff, repo.fireTimer("e81656cb0d45", 0))
        assertFalse(unit.state.power)
        assertNull(repository().units.value.units.single().offAt)
    }

    @Test
    fun `an early alarm is put back instead of switching off`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.setTimer("e81656cb0d45", 60)
        assertEquals(Repository.TimerOutcome.NotDue, repo.fireTimer("e81656cb0d45", 0))
        assertEquals(2, timers.scheduled.size)
    }

    @Test
    fun `an unreachable unit is retried every 30 seconds, then given up`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.setTimer("e81656cb0d45", 1)
        unit.close()
        clock += 60_000L

        val first = repo.fireTimer("e81656cb0d45", 0)
        assertEquals(Repository.TimerOutcome.Retrying(1), first)
        assertEquals(Triple("e81656cb0d45", clock + 30_000L, 1), timers.scheduled.last())

        val last = repo.fireTimer("e81656cb0d45", Repository.TIMER_ATTEMPTS - 1)
        assertTrue(last is Repository.TimerOutcome.GaveUp)
        assertNull(repo.units.value.units.single().offAt)
    }

    @Test
    fun `switching off by hand cancels the timer`() = runBlocking {
        val repo = repository()
        repo.add("127.0.0.1")
        repo.setTimer("e81656cb0d45", 120)
        repo.apply("e81656cb0d45") { it.copy(power = false) }
        assertEquals(listOf("e81656cb0d45"), timers.cancelled)
        assertNull(repo.units.value.units.single().offAt)
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

    @Test(expected = NoUnitAtAddressException::class)
    fun `adding an address where no unit answers fails clearly`(): Unit = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        repository(port = closedPort).add("127.0.0.1")
    }
}
