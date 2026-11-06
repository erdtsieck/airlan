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
 * Timers as alarms, so they fire with the app closed and the phone asleep.
 *
 * Every timer gets an inexact alarm, which needs no permission, and on top of that an exact
 * one when the user allows exact alarms. Revoking that permission makes Android delete the
 * app's exact alarms (and kill it) without telling it; the inexact alarm survives and still
 * switches the unit, if possibly some minutes late. Whichever goes off first does the work;
 * [io.github.erdtsieck.airlan.data.Repository.fireTimer] makes the other a no-op.
 */
class AlarmTimerScheduler(private val context: Context) : TimerScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** One PendingIntent per unit, action and kind, told apart by their data URI. */
    private fun intent(airconId: String, action: TimerAction, kind: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TimerReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(
                Uri.Builder().scheme("airlan").authority("timer")
                    .appendPath(airconId).appendPath(action.key).appendPath(kind).build(),
            )
            .putExtra(EXTRA_AIRCON_ID, airconId)
            .putExtra(EXTRA_TIMER_ACTION, action.key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun schedule(airconId: String, action: TimerAction, atMillis: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent(airconId, action, INEXACT))
        if (canScheduleExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent(airconId, action, EXACT))
        } else {
            alarms.cancel(intent(airconId, action, EXACT))
        }
    }

    override fun cancel(airconId: String, action: TimerAction) {
        alarms.cancel(intent(airconId, action, EXACT))
        alarms.cancel(intent(airconId, action, INEXACT))
    }

    companion object {
        const val ACTION_FIRE = "io.github.erdtsieck.airlan.action.FIRE_TIMER"
        const val EXTRA_AIRCON_ID = "airconId"
        const val EXTRA_TIMER_ACTION = "timerAction"
        private const val EXACT = "exact"
        private const val INEXACT = "inexact"

        fun canScheduleExact(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** An alarm went off: switch the unit on or off, and tell the user if that keeps failing. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmTimerScheduler.ACTION_FIRE) return
        val airconId = intent.getStringExtra(AlarmTimerScheduler.EXTRA_AIRCON_ID) ?: return
        val key = intent.getStringExtra(AlarmTimerScheduler.EXTRA_TIMER_ACTION)
        val action = TimerAction.entries.firstOrNull { it.key == key } ?: return
        val pending = goAsync()
        receiverScope.launch {
            try {
                val outcome = context.repository.fireTimer(airconId, action)
                if (outcome is TimerOutcome.GaveUp) notifyFailure(context, airconId, action)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyFailure(context: Context, airconId: String, action: TimerAction) {
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
            .setContentText(context.getString(R.string.timer_failed_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.timer_failed_text)))
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
