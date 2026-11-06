package io.github.erdtsieck.airlan.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What a timer does when it fires: write [power] and nothing else, so switching on brings
 * back the unit's last mode, temperature and airflow. A unit has at most one pending timer
 * per action (see [StoredUnit.timers]). [key] names the action in the state file and in
 * alarm URIs; it must not change.
 */
enum class TimerAction(val power: Boolean, val key: String) {
    ON(true, "on"),
    OFF(false, "off"),
}

/**
 * A pending switch-on or switch-off, retries included. This is the single source of truth
 * for its alarm: Android can drop alarms (reboot, update, a revoked exact-alarm permission),
 * so the alarm is always derived from this, never the other way round.
 */
data class UnitTimer(
    /** Epoch millis at which the user wants the unit switched. */
    val at: Long,
    /** Attempts to switch the unit that have failed so far. */
    val failedAttempts: Int = 0,
    /** Epoch millis of the next attempt after a failure; null before the first attempt. */
    val retryAt: Long? = null,
) {
    /** When the alarm should go off next. */
    val dueAt get() = retryAt ?: at
}

/**
 * Schedules the moment a unit's timer fires. Backed by AlarmManager on the device. It
 * carries no state of its own: what to do when it fires is read from the stored [UnitTimer].
 */
interface TimerScheduler {
    fun schedule(airconId: String, action: TimerAction, atMillis: Long)
    fun cancel(airconId: String, action: TimerAction)
}

/**
 * The next moment after [now] at which the clock in [zone] reads [hour]:[minute]: today if
 * that is still ahead, else tomorrow. A time that a daylight-saving change skips is moved
 * forward by the length of the gap, as [ZonedDateTime.of] does.
 */
fun nextOccurrence(now: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    val at = today.toLocalDate().atTime(hour, minute).atZone(zone)
    val next = if (at.toInstant().toEpochMilli() > now) at else today.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
    return next.toInstant().toEpochMilli()
}
