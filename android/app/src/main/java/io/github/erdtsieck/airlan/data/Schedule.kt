package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Mode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The mode and temperature a rule switches a unit on in. */
data class Setting(val mode: Mode, val presetTemp: Double)

/**
 * One line of a unit's schedule: switch the unit on or off when the clock reads
 * [minuteOfDay] on one of [days]. A rule without days runs once, at the next time the clock
 * reads [minuteOfDay], and then turns itself off, like a one-time alarm on a phone.
 */
data class ScheduleRule(
    val id: String,
    val action: TimerAction,
    val minuteOfDay: Int,
    val days: Set<DayOfWeek>,
    /** What an on-rule switches the unit on in; null brings back its last mode and temperature. */
    val setting: Setting? = null,
    val enabled: Boolean = true,
) {
    init {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "not a time of day: $minuteOfDay" }
        require(setting == null || action == TimerAction.ON) { "only an on-rule has a setting" }
    }

    val once get() = days.isEmpty()

    /** [state] as this rule leaves it. Fan only has no temperature, so it keeps the one it had. */
    fun applyTo(state: AirconState): AirconState = when {
        action == TimerAction.OFF -> state.copy(power = false)
        setting == null -> state.copy(power = true)
        setting.mode == Mode.FAN -> state.copy(power = true, mode = Mode.FAN)
        else -> state.copy(power = true, mode = setting.mode, presetTemp = setting.presetTemp)
    }

    /**
     * The first time after [after] at which this rule switches, on a day after [pausedUntil]
     * (when set). A time that a daylight-saving change skips moves forward by the gap.
     */
    fun occurrenceAfter(after: Long, zone: ZoneId, pausedUntil: LocalDate? = null): Long? {
        var date = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        if (pausedUntil != null && !date.isAfter(pausedUntil)) date = pausedUntil.plusDays(1)
        repeat(8) {
            if (once || date.dayOfWeek in days) {
                val at = date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()
                if (at > after) return at
            }
            date = date.plusDays(1)
        }
        return null
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * The schedule's next switch, retries included. Like [UnitTimer], this is the single source
 * of truth for the schedule's alarm, which is always derived from it.
 */
data class ScheduleRun(
    val ruleId: String,
    /** Epoch millis at which the rule wants the unit switched. */
    val at: Long,
    val failedAttempts: Int = 0,
    /** Epoch millis of the next attempt after a failure; null before the first attempt. */
    val retryAt: Long? = null,
    /** An attempt failed while the phone was on the unit's network, so the unit itself did not answer. */
    val failedAtHome: Boolean = false,
) {
    val dueAt get() = retryAt ?: at
}

/** How the schedule's last switch went: done at [doneAt], or not at all (null). */
data class ScheduleOutcome(val action: TimerAction, val at: Long, val doneAt: Long?)

/**
 * A unit's schedule. [run] is kept in step with the rules by [replanned]: every change to the
 * rules, the pause or the skip goes through it.
 */
data class Schedule(
    val rules: List<ScheduleRule> = emptyList(),
    /** No rule runs on this day or before it. */
    val pausedUntil: LocalDate? = null,
    /** The one switch the user chose to skip, by its time. */
    val skipAt: Long? = null,
    val run: ScheduleRun? = null,
    val last: ScheduleOutcome? = null,
) {
    /** The first switch after [after]: which rule, and when. */
    fun next(after: Long, zone: ZoneId): Pair<ScheduleRule, Long>? = rules
        .filter { it.enabled }
        .mapNotNull { rule ->
            val at = rule.occurrenceAfter(after, zone, pausedUntil) ?: return@mapNotNull null
            val unskipped = if (at == skipAt) rule.occurrenceAfter(at, zone, pausedUntil) else at
            unskipped?.let { rule to it }
        }
        .minByOrNull { it.second }

    /**
     * Until when a switch that failed keeps being tried: until the unit's next switch, so an
     * on and the off after it never land in the wrong order, and at most [MAX_WINDOW_MS].
     */
    fun windowEnd(run: ScheduleRun, zone: ZoneId): Long =
        minOf(run.at + MAX_WINDOW_MS, next(run.at, zone)?.second ?: Long.MAX_VALUE)

    /**
     * This schedule after [edited] replaced [this]: the next switch is planned afresh from
     * [now], except that a switch still being retried stays when its rule did not change.
     */
    fun replanned(edited: Schedule, now: Long, zone: ZoneId): Schedule {
        val retrying = run?.takeIf { r ->
            val rule = rules.firstOrNull { it.id == r.ruleId }
            r.failedAttempts > 0 && rule != null && rule.enabled && rule == edited.rules.firstOrNull { it.id == r.ruleId } &&
                edited.pausedUntil == pausedUntil && edited.skipAt == skipAt
        }
        return edited.planned(now, zone, keep = retrying)
    }

    /** This schedule with its next switch planned from [now] (or [keep] when given), and a past skip forgotten. */
    fun planned(now: Long, zone: ZoneId, keep: ScheduleRun? = null): Schedule {
        val skip = skipAt?.takeIf { it > now }
        val pausedTill = pausedUntil?.takeIf { !it.isBefore(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) }
        val cleaned = copy(skipAt = skip, pausedUntil = pausedTill)
        return cleaned.copy(run = keep ?: cleaned.next(now, zone)?.let { (rule, at) -> ScheduleRun(rule.id, at) })
    }

    companion object {
        /** The longest a schedule's switch keeps being tried, when no other switch comes first. */
        const val MAX_WINDOW_MS = 12 * 60 * 60_000L
    }
}

/** A stretch of a day during which the schedule keeps the unit on, in minutes from midnight. */
data class OnStretch(val day: DayOfWeek, val fromMinute: Int, val toMinute: Int, val setting: Setting?)

/**
 * What the repeating, enabled rules do to a week, for drawing it: from each switch-on to the
 * next switch-off, across midnight and around the week. A schedule that never switches off
 * would show every day as on; its switch-ons are shown as a short mark instead.
 */
fun weekOn(rules: List<ScheduleRule>): List<OnStretch> {
    val week = 7 * ScheduleRule.MINUTES_PER_DAY
    val events = rules.filter { it.enabled && !it.once }
        .flatMap { r -> r.days.map { d -> (d.value - 1) * ScheduleRule.MINUTES_PER_DAY + r.minuteOfDay to r } }
        .sortedBy { it.first }
    if (events.none { it.second.action == TimerAction.ON }) return emptyList()
    val stretches = mutableListOf<Triple<Int, Int, Setting?>>()
    if (events.none { it.second.action == TimerAction.OFF }) {
        events.forEach { (m, r) -> stretches += Triple(m, minOf(m + MARK_MINUTES, week), r.setting) }
    } else {
        // The state at the start of the week is the state the week ends in.
        val last = events.last().second
        var onSince: Int? = if (last.action == TimerAction.ON) 0 else null
        var setting = if (last.action == TimerAction.ON) last.setting else null
        for ((m, r) in events) {
            if (r.action == TimerAction.ON && onSince == null) {
                onSince = m
                setting = r.setting
            } else if (r.action == TimerAction.OFF && onSince != null) {
                if (m > onSince) stretches += Triple(onSince, m, setting)
                onSince = null
            }
        }
        if (onSince != null) stretches += Triple(onSince, week, setting)
    }
    return stretches.flatMap { (from, to, setting) ->
        (from / ScheduleRule.MINUTES_PER_DAY..(to - 1) / ScheduleRule.MINUTES_PER_DAY).map { d ->
            val start = d * ScheduleRule.MINUTES_PER_DAY
            OnStretch(DayOfWeek.of(d + 1), maxOf(from, start) - start, minOf(to, start + ScheduleRule.MINUTES_PER_DAY) - start, setting)
        }
    }
}

/** How long a switch-on is drawn in a schedule that never switches off. */
private const val MARK_MINUTES = 30
