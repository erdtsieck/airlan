package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.wfrac.Mode
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val weekdays = setOf(MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, FRIDAY)

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun rule(id: String, action: TimerAction, time: String, days: Set<DayOfWeek> = weekdays, setting: Setting? = null): ScheduleRule {
        val (h, m) = time.split(':').map(String::toInt)
        return ScheduleRule(id, action, h * 60 + m, days, setting)
    }

    // 6 November 2026 is a Friday.
    private val fridayEvening = at("2026-11-06T21:30")

    @Test
    fun `a rule's next switch is on its next day at its time`() {
        assertEquals(at("2026-11-09T06:45"), rule("a", TimerAction.ON, "06:45").occurrenceAfter(fridayEvening, zone))
        assertEquals(at("2026-11-06T22:15"), rule("a", TimerAction.OFF, "22:15").occurrenceAfter(fridayEvening, zone))
        assertEquals(at("2026-11-07T09:00"), rule("a", TimerAction.ON, "09:00", setOf(SATURDAY, SUNDAY)).occurrenceAfter(fridayEvening, zone))
        // A one-time rule: the next time the clock reads it, whatever the day.
        assertEquals(at("2026-11-07T07:00"), rule("a", TimerAction.ON, "07:00", emptySet()).occurrenceAfter(fridayEvening, zone))
        // A time is strictly after: the switch at 21:30 itself is not the next one.
        assertEquals(at("2026-11-07T21:30"), rule("a", TimerAction.ON, "21:30", emptySet()).occurrenceAfter(fridayEvening, zone))
    }

    @Test
    fun `a time that the clocks skip moves forward by the gap`() {
        // 02:30 does not exist on the night the clocks go forward; it becomes 03:30.
        val nightly = rule("a", TimerAction.ON, "02:30", DayOfWeek.entries.toSet())
        assertEquals(at("2027-03-28T03:30"), nightly.occurrenceAfter(at("2027-03-27T23:00"), zone))
    }

    @Test
    fun `nothing switches on or before the day the schedule is paused until`() {
        val r = rule("a", TimerAction.ON, "06:45")
        assertEquals(at("2026-11-16T06:45"), r.occurrenceAfter(fridayEvening, zone, pausedUntil = LocalDate.of(2026, 11, 13)))
    }

    @Test
    fun `the next switch is the earliest enabled rule's, past a skipped one`() {
        val on = rule("on", TimerAction.ON, "06:45")
        val off = rule("off", TimerAction.OFF, "08:30")
        val weekend = rule("we", TimerAction.ON, "09:00", setOf(SATURDAY, SUNDAY), Setting(Mode.HEAT, 20.0))
        val s = Schedule(listOf(on, off, weekend))
        assertEquals(weekend to at("2026-11-07T09:00"), s.next(fridayEvening, zone))
        assertEquals(on to at("2026-11-09T06:45"), s.copy(rules = listOf(on, off, weekend.copy(enabled = false))).next(fridayEvening, zone))
        assertEquals(weekend to at("2026-11-08T09:00"), s.copy(skipAt = at("2026-11-07T09:00")).next(fridayEvening, zone))
        assertNull(Schedule(listOf(on.copy(enabled = false))).next(fridayEvening, zone))
    }

    @Test
    fun `a failed switch is tried until the next switch, and at most twelve hours`() {
        val s = Schedule(listOf(rule("on", TimerAction.ON, "17:30"), rule("off", TimerAction.OFF, "22:30")))
        val run = ScheduleRun("on", at("2026-11-09T17:30"))
        assertEquals(at("2026-11-09T22:30"), s.windowEnd(run, zone))
        val onlyOn = Schedule(listOf(rule("on", TimerAction.ON, "17:30", setOf(MONDAY))))
        assertEquals(run.at + Schedule.MAX_WINDOW_MS, onlyOn.windowEnd(run, zone))
    }

    @Test
    fun `an edit replans, but keeps a retry of a rule it did not change`() {
        val on = rule("on", TimerAction.ON, "17:30")
        val off = rule("off", TimerAction.OFF, "22:30")
        val retrying = ScheduleRun("on", at("2026-11-09T17:30"), failedAttempts = 2, retryAt = at("2026-11-09T17:32"))
        val s = Schedule(listOf(on, off), run = retrying)
        val now = at("2026-11-09T17:31")
        val extra = rule("x", TimerAction.ON, "06:00")
        assertEquals(retrying, s.replanned(s.copy(rules = listOf(on, off, extra)), now, zone).run)
        assertEquals(ScheduleRun("on", at("2026-11-09T17:45")), s.replanned(s.copy(rules = listOf(on.copy(minuteOfDay = 17 * 60 + 45), off)), now, zone).run)
        assertEquals(ScheduleRun("off", at("2026-11-09T22:30")), s.replanned(s.copy(rules = listOf(off)), now, zone).run)
    }

    @Test
    fun `a past pause and a past skip are forgotten when planning`() {
        val s = Schedule(listOf(rule("on", TimerAction.ON, "06:45")), pausedUntil = LocalDate.of(2026, 11, 1), skipAt = at("2026-11-02T06:45"))
        val planned = s.planned(fridayEvening, zone)
        assertNull(planned.pausedUntil)
        assertNull(planned.skipAt)
        assertEquals(ScheduleRun("on", at("2026-11-09T06:45")), planned.run)
    }

    @Test
    fun `a rule switches the unit as asked`() {
        val state = io.github.erdtsieck.airlan.wfrac.AirconState(
            power = false, mode = Mode.COOL, presetTemp = 19.0, airFlow = 2, windUD = 0, windLR = 0, entrust = false,
            coolHotJudge = true, modelNr = 1, modelNrRaw = 1, vacant = false, selfClean = false,
        )
        assertEquals(state.copy(power = true), rule("a", TimerAction.ON, "07:00").applyTo(state))
        assertEquals(state.copy(power = true, mode = Mode.HEAT, presetTemp = 22.5), rule("a", TimerAction.ON, "07:00", setting = Setting(Mode.HEAT, 22.5)).applyTo(state))
        assertEquals(state.copy(power = true, mode = Mode.FAN), rule("a", TimerAction.ON, "07:00", setting = Setting(Mode.FAN, 22.5)).applyTo(state))
        assertEquals(state.copy(power = false), rule("a", TimerAction.OFF, "07:00").applyTo(state.copy(power = true)))
    }

    @Test
    fun `the week strip runs from each switch-on to the next switch-off`() {
        val heat = Setting(Mode.HEAT, 21.0)
        val stretches = weekOn(
            listOf(
                rule("on", TimerAction.ON, "06:45", setting = heat),
                rule("off", TimerAction.OFF, "08:30"),
                rule("x", TimerAction.ON, "12:00", emptySet()), // one-time: not part of the week
            ),
        )
        assertEquals(weekdays.sorted().map { OnStretch(it, 6 * 60 + 45, 8 * 60 + 30, heat) }, stretches)
    }

    @Test
    fun `a stretch runs across midnight and around the week`() {
        val stretches = weekOn(listOf(rule("on", TimerAction.ON, "22:00", setOf(SUNDAY)), rule("off", TimerAction.OFF, "06:00", setOf(MONDAY))))
        assertEquals(listOf(OnStretch(MONDAY, 0, 6 * 60, null), OnStretch(SUNDAY, 22 * 60, 24 * 60, null)), stretches)
    }

    @Test
    fun `a schedule that never switches off shows short marks, and one that never switches on nothing`() {
        assertEquals(listOf(OnStretch(SATURDAY, 9 * 60, 9 * 60 + 30, null)), weekOn(listOf(rule("on", TimerAction.ON, "09:00", setOf(SATURDAY)))))
        assertTrue(weekOn(listOf(rule("off", TimerAction.OFF, "23:00"))).isEmpty())
    }
}
