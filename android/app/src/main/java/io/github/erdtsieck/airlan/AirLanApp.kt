package io.github.erdtsieck.airlan

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.erdtsieck.airlan.data.Repository
import io.github.erdtsieck.airlan.data.UnitStore
import io.github.erdtsieck.airlan.discovery.Discovery
import io.github.erdtsieck.airlan.timer.AlarmTimerScheduler
import java.io.File

class AirLanApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(
            store = UnitStore(File(filesDir, "state.json")),
            timers = AlarmTimerScheduler(this),
            localAddresses = { Discovery.localAddresses(this) },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TIMER_CHANNEL, getString(R.string.channel_timer), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val TIMER_CHANNEL = "timer"
    }
}

val android.content.Context.repository: Repository get() = (applicationContext as AirLanApp).repository
