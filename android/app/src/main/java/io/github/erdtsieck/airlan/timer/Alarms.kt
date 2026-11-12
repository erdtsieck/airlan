package io.github.erdtsieck.airlan.timer

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.erdtsieck.airlan.AirLanApp
import io.github.erdtsieck.airlan.R
import io.github.erdtsieck.airlan.data.Alarm
import io.github.erdtsieck.airlan.data.Repository.TimerOutcome
import io.github.erdtsieck.airlan.data.TimerAction
import io.github.erdtsieck.airlan.data.TimerScheduler
import io.github.erdtsieck.airlan.repository
import io.github.erdtsieck.airlan.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The switch-off timer and the schedule as alarms, so they fire with the app closed and the
 * phone asleep.
 *
 * Every timer gets an inexact alarm, which needs no permission, and on top of that an exact
 * one when the user allows exact alarms. Revoking that permission makes Android delete the
 * app's exact alarms (and kill it) without telling it; the inexact alarm survives and still
 * switches the unit, if possibly some minutes late. Whichever goes off first does the work;
 * [io.github.erdtsieck.airlan.data.Repository.fireOffTimer] and
 * [io.github.erdtsieck.airlan.data.Repository.fireSchedule] make the other a no-op.
 */
class AlarmTimerScheduler(private val context: Context) : TimerScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** One PendingIntent per unit, alarm and kind, told apart by their data URI. */
    private fun intent(airconId: String, alarm: Alarm, kind: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimerReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(
                Uri.Builder().scheme("airlan").authority("timer")
                    .appendPath(airconId).appendPath(alarm.key).appendPath(kind).build(),
            )
            .putExtra(EXTRA_AIRCON_ID, airconId)
            .putExtra(EXTRA_ALARM, alarm.key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun schedule(airconId: String, alarm: Alarm, atMillis: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent(airconId, alarm, INEXACT))
        if (canScheduleExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent(airconId, alarm, EXACT))
        } else {
            alarms.cancel(intent(airconId, alarm, EXACT))
        }
    }

    override fun cancel(airconId: String, alarm: Alarm) {
        alarms.cancel(intent(airconId, alarm, EXACT))
        alarms.cancel(intent(airconId, alarm, INEXACT))
    }

    companion object {
        const val ACTION_FIRE = "io.github.erdtsieck.airlan.action.FIRE_TIMER"
        const val EXTRA_AIRCON_ID = "airconId"
        /** Named "timerAction" from when the alarms were per action; the switch-off timer's alarms still carry it. */
        const val EXTRA_ALARM = "timerAction"
        private const val EXACT = "exact"
        private const val INEXACT = "inexact"

        fun canScheduleExact(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * An alarm went off: switch the unit, and tell the user when that keeps failing although the
 * phone was on the unit's network. AirLAN 0.4's switch-on alarms ("on") are not answered: the
 * update cancelled them, and their timer is now a schedule rule with an alarm of its own.
 */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmTimerScheduler.ACTION_FIRE) return
        val airconId = intent.getStringExtra(AlarmTimerScheduler.EXTRA_AIRCON_ID) ?: return
        val key = intent.getStringExtra(AlarmTimerScheduler.EXTRA_ALARM)
        val alarm = Alarm.entries.firstOrNull { it.key == key } ?: return
        val pending = goAsync()
        receiverScope.launch {
            try {
                val repo = context.repository
                when (alarm) {
                    Alarm.OFF_TIMER -> if (repo.fireOffTimer(airconId) is TimerOutcome.GaveUp) {
                        notifyFailure(context, airconId, TimerAction.OFF, R.string.timer_failed_text)
                    }
                    Alarm.SCHEDULE -> if (repo.fireSchedule(airconId) is TimerOutcome.GaveUp) {
                        val action = repo.units.value.units.firstOrNull { it.airconId == airconId }?.schedule?.last?.action
                        if (action != null) notifyFailure(context, airconId, action, R.string.schedule_failed_text)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyFailure(context: Context, airconId: String, action: TimerAction, text: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val name = context.repository.units.value.units.firstOrNull { it.airconId == airconId }?.name
            ?: context.getString(R.string.new_unit, 1)
        val title = context.getString(
            when (action) {
                TimerAction.ON -> R.string.timer_failed_on_title
                TimerAction.OFF -> R.string.timer_failed_title
            },
            name,
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AirLanApp.TIMER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify("$airconId/${action.key}".hashCode(), notification)
    }
}

/**
 * Alarms do not survive a reboot or an app update. These broadcasts start the process, which
 * sets the alarms again (see AirLanApp); a granted exact-alarm permission can also arrive while
 * the process runs, and then upgrades the alarms to exact ones.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> context.repository.rescheduleAll()
        }
    }
}
