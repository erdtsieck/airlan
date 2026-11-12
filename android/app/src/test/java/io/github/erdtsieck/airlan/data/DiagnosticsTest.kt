package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.discovery.LocalAddress
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Mode
import io.github.erdtsieck.airlan.wfrac.RequestTrace
import io.github.erdtsieck.airlan.wfrac.Scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.DayOfWeek
import java.time.ZoneId
import org.junit.Test

class DiagnosticsTest {
    private val unit = StoredUnit("e81656cb0d45", "Woonkamer", "192.168.68.109", scheme = Scheme.HTTP, modelNrRaw = 1, reportsVaneLR = true)
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val home = listOf(LocalAddress("192.168.68.20", 24))
    private val state = AirconState(
        power = true, mode = Mode.AUTO, presetTemp = 22.0, airFlow = 0, windUD = 0, windLR = 0, entrust = true,
        coolHotJudge = true, modelNr = 1, modelNrRaw = 1, vacant = false, selfClean = false,
    )

    @Test
    fun `masks addresses but keeps their last number`() {
        assertEquals("connect to /x.x.x.109:51443 failed", Diagnostics.maskAddresses("connect to /192.168.68.109:51443 failed"))
    }

    @Test
    fun `reports firmware, state and requests without the name, address or MAC`() {
        val trace = RequestTrace()
        trace.module = RequestTrace.Module("WF-RAC", "010", "131", 3)
        trace.add(RequestTrace.Entry(0, "getAirconStat", 120, 0, null))
        trace.add(RequestTrace.Entry(1_000, "setAirconStat", 300, 1, null))
        trace.add(RequestTrace.Entry(2_000, "getAirconStat", 6000, null, "SocketTimeoutException: connect to /192.168.68.109 timed out"))

        val report = Diagnostics.report("0.3.0", "16 (API 36)", unit, state, trace, home, zone, 0)

        assertTrue(report, report.contains("App 0.3.0, Android 16 (API 36)"))
        assertTrue(report, report.contains("firmType WF-RAC, wireless 010, mcu 131, accounts 3"))
        assertTrue(report, report.contains("...0d45 at x.x.x.109, transport http"))
        assertTrue(report, report.contains("mode auto"))
        assertTrue(report, report.contains("setAirconStat 300 ms result 1"))
        assertTrue(report, report.contains("failures: 2 of 3"))
        assertFalse(report, report.contains("Woonkamer"))
        assertFalse(report, report.contains("192.168"))
        assertFalse(report, report.contains("e81656cb"))
    }

    @Test
    fun `a request that queued long and events such as a move are reported, addresses masked`() {
        val trace = RequestTrace()
        trace.add(RequestTrace.Entry(0, "getAirconStat", 10_000, null, "connected, no answer within 10 s", waitedMs = 4_200))
        trace.add(RequestTrace.Entry(1_000, "getAirconStat", 90, 0, null, waitedMs = 200))
        trace.note("address changed from 192.168.68.116 to 192.168.68.120", 2_000)

        val report = Diagnostics.report("0.3.2", "16", unit, null, trace, home, zone, 0)

        assertTrue(report, report.contains("getAirconStat 10000 ms, queued 4200 ms connected, no answer within 10 s"))
        assertTrue(report, report.contains("getAirconStat 90 ms result 0"))
        assertTrue(report, report.contains("Events:"))
        assertTrue(report, report.contains("address changed from x.x.x.116 to x.x.x.120"))
    }

    @Test
    fun `a demo unit says so and has no module`() {
        val report = Diagnostics.report("0.3.0", "16", StoredUnit("demo", "Demo", "", demo = true), null, null, emptyList(), zone, 0)
        assertTrue(report, report.contains("Unit: demo (simulated)"))
        assertFalse(report, report.contains("Network:"))
        assertTrue(report, report.contains("State: not read"))
    }

    @Test
    fun `says which network the phone is on and whether the unit is in it, masked`() {
        val atHome = Diagnostics.report("0.5.0", "16", unit, null, null, home, zone, 0)
        assertTrue(atHome, atHome.contains("Network: x.x.x.0/24, unit inside it"))
        val elsewhere = Diagnostics.report("0.5.0", "16", unit, null, null, listOf(LocalAddress("10.0.4.7", 22)), zone, 0)
        assertTrue(elsewhere, elsewhere.contains("Network: x.x.x.0/22, unit outside it"))
        assertFalse(elsewhere, elsewhere.contains("10.0"))
        val offline = Diagnostics.report("0.5.0", "16", unit, null, null, emptyList(), zone, 0)
        assertTrue(offline, offline.contains("Network: no Wi-Fi or Ethernet"))
    }

    @Test
    fun `sums up the schedule`() {
        val rule = ScheduleRule("a", TimerAction.ON, 6 * 60 + 45, setOf(DayOfWeek.MONDAY))
        // 1 January 1970 was a Thursday; 07:30 CET is 06:30 UTC.
        val withSchedule = unit.copy(
            schedule = Schedule(
                rules = listOf(rule, ScheduleRule("b", TimerAction.OFF, 8 * 60, emptySet(), enabled = false)),
                run = ScheduleRun("a", 6 * 3_600_000L + 45 * 60_000L, failedAttempts = 2, retryAt = 1, failedAtHome = true),
                last = ScheduleOutcome(TimerAction.OFF, 0, null),
            ),
        )
        val report = Diagnostics.report("0.5.0", "16", withSchedule, null, null, home, zone, 0)
        assertTrue(report, report.contains("Schedule: 2 rules, 1 enabled; next on Thu 07:45, 2 failed attempts (on its network); last off Thu 01:00 not done"))
        assertTrue(Diagnostics.report("0.5.0", "16", unit, null, null, home, zone, 0).contains("Schedule: none"))
    }
}
