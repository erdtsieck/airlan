package io.github.erdtsieck.airlan.timer

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.erdtsieck.airlan.AirLanApp
import io.github.erdtsieck.airlan.R
import io.github.erdtsieck.airlan.data.Repository.TimerOutcome
import io.github.erdtsieck.airlan.data.TimerScheduler
import io.github.erdtsieck.airlan.repository
import io.github.erdtsieck.airlan.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Off-timers as alarms, so they fire with the app closed and the phone asleep. Exact when the
 * user allows it; otherwise Android may deliver the alarm some minutes late.
 */
class AlarmTimerScheduler(private val context: Context) : TimerScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun intent(airconId: String, attempt: Int = 0): PendingIntent = PendingIntent.getBroadcast(
        context,
        airconId.hashCode(),
        Intent(context, OffTimerReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_AIRCON_ID, airconId)
            .putExtra(EXTRA_ATTEMPT, attempt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun schedule(airconId: String, atMillis: Long, attempt: Int) {
        val pending = intent(airconId, attempt)
        if (canScheduleExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    override fun cancel(airconId: String) = alarms.cancel(intent(airconId))

    companion object {
        const val ACTION_FIRE = "io.github.erdtsieck.airlan.action.FIRE_TIMER"
        const val EXTRA_AIRCON_ID = "airconId"
        const val EXTRA_ATTEMPT = "attempt"

        fun canScheduleExact(context: Context) =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** The alarm went off: switch the unit off, and tell the user if that keeps failing. */
class OffTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmTimerScheduler.ACTION_FIRE) return
        val airconId = intent.getStringExtra(AlarmTimerScheduler.EXTRA_AIRCON_ID) ?: return
        val attempt = intent.getIntExtra(AlarmTimerScheduler.EXTRA_ATTEMPT, 0)
        val pending = goAsync()
        receiverScope.launch {
            try {
                val outcome = context.repository.fireTimer(airconId, attempt)
                if (outcome is TimerOutcome.GaveUp) notifyFailure(context, airconId)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyFailure(context: Context, airconId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val name = context.repository.units.value.units.firstOrNull { it.airconId == airconId }?.name
            ?: context.getString(R.string.new_unit, 1)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AirLanApp.TIMER_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.timer_failed_title, name))
            .setContentText(context.getString(R.string.timer_failed_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.timer_failed_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(airconId.hashCode(), notification)
    }
}

/** Alarms do not survive a reboot or an app update; exact alarms also change with the permission. */
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
