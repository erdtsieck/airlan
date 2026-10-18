package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Mode
import io.github.erdtsieck.airlan.wfrac.RequestTrace
import io.github.erdtsieck.airlan.wfrac.Scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val unit = StoredUnit("e81656cb0d45", "Woonkamer", "192.168.68.109", scheme = Scheme.HTTP, modelNrRaw = 1, reportsVaneLR = true)
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

        val report = Diagnostics.report("0.3.0", "16 (API 36)", unit, state, trace, 0)

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
    fun `a demo unit says so and has no module`() {
        val report = Diagnostics.report("0.3.0", "16", StoredUnit("demo", "Demo", "", demo = true), null, null, 0)
        assertTrue(report, report.contains("Unit: demo (simulated)"))
        assertTrue(report, report.contains("State: not read"))
    }
}
