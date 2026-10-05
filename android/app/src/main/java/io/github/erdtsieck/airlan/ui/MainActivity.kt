package io.github.erdtsieck.airlan.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.erdtsieck.airlan.timer.AlarmTimerScheduler
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirLanTheme {
                var granted by remember { mutableStateOf(localNetworkGranted()) }
                var askedOnce by remember { mutableStateOf(false) }
                val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    granted = it
                    askedOnce = true
                }
                LifecycleResumeEffect(Unit) {
                    granted = localNetworkGranted()
                    onPauseOrDispose {}
                }
                if (!granted) {
                    PermissionScreen(
                        denied = askedOnce,
                        onAllow = { request.launch(LOCAL_NETWORK) },
                        onSettings = { openAppSettings() },
                    )
                } else {
                    App(vm)
                }
            }
        }
    }

    private fun localNetworkGranted() =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    companion object {
        // Android 17 (API 37) made the local network a runtime permission. Before that,
        // INTERNET covers it and the permission does not exist.
        private const val LOCAL_NETWORK_SDK = 37
        private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { vm.scanIfEmpty() }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(10_000)
            }
        }
    }

    var exactAlarmAsk by remember { mutableStateOf<Int?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun startTimer(minutes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Only used to say so when a timer could not switch a unit off.
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.setTimer(minutes)
    }

    exactAlarmAsk?.let { minutes ->
        ExactAlarmDialog(
            onAllow = {
                exactAlarmAsk = null
                startTimer(minutes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)),
                    )
                }
            },
            onSkip = {
                exactAlarmAsk = null
                startTimer(minutes)
            },
        )
    }

    when {
        state.screen == Screen.MANAGE || (state.loaded && state.units.isEmpty()) -> ManageScreen(
            state = state,
            onDone = { vm.showManage(false) },
            onScan = vm::scan,
            onAdd = vm::add,
            onRename = vm::rename,
            onForget = vm::forget,
        )
        else -> UnitScreen(
            state = state,
            onSelect = vm::select,
            onManage = { vm.showManage(true) },
            onPower = vm::setPower,
            onMode = vm::setMode,
            onNudge = vm::nudgeTemp,
            onTimer = { minutes ->
                if (AlarmTimerScheduler.canScheduleExact(context)) startTimer(minutes) else exactAlarmAsk = minutes
            },
            onCancelTimer = vm::cancelTimer,
            onRename = vm::rename,
        )
    }
}
