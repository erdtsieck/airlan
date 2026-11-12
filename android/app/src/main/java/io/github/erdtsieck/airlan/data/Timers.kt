package io.github.erdtsieck.airlan.data

/**
 * What a switch does: write [power] (and, for a schedule rule with a setting, the mode and
 * temperature). [key] names the action in the state file; it must not change.
 */
enum class TimerAction(val power: Boolean, val key: String) {
    ON(true, "on"),
    OFF(false, "off"),
}

/**
 * The pending switch-off timer, retries included. This is the single source of truth for its
 * alarm: Android can drop alarms (reboot, update, a revoked exact-alarm permission), so the
 * alarm is always derived from this, never the other way round.
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
 * The alarms a unit can have: its switch-off timer and its schedule's next switch. [key] names
 * the alarm in its intent; "off" is what the switch-off timer's alarms were set with before
 * the schedule existed, so they keep working across the update.
 */
enum class Alarm(val key: String) {
    OFF_TIMER("off"),
    SCHEDULE("schedule"),
}

/**
 * Sets the moment a unit's alarm goes off. Backed by AlarmManager on the device. It carries
 * no state of its own: what to do when it goes off is read from the stored [UnitTimer] or
 * [ScheduleRun].
 */
interface TimerScheduler {
    fun schedule(airconId: String, alarm: Alarm, atMillis: Long)
    fun cancel(airconId: String, alarm: Alarm)
}
