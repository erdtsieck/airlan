package io.github.erdtsieck.airlan.ui

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erdtsieck.airlan.R
import io.github.erdtsieck.airlan.data.LocalNetworkNotAllowedException
import io.github.erdtsieck.airlan.data.NoUnitAtAddressException
import io.github.erdtsieck.airlan.data.StoredUnit
import io.github.erdtsieck.airlan.discovery.Discovery
import io.github.erdtsieck.airlan.repository
import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Mode
import io.github.erdtsieck.airlan.wfrac.WfRacException
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A unit as shown: what is stored about it, plus its last known live state or why it is missing.
 * A unit that [needsPermission] is not contacted at all, so it has neither state nor error.
 */
data class UnitUi(val stored: StoredUnit, val state: AirconState?, @StringRes val error: Int?, val needsPermission: Boolean = false) {
    val online get() = state != null

    /** One word for where the unit stands. */
    @get:StringRes
    val availability get() = when {
        online -> R.string.online
        needsPermission -> R.string.needs_permission
        else -> R.string.unreachable
    }
}

enum class Screen { UNIT, MANAGE }

/** A line under the controls: a string, or a plural when [plural] is set, with [arg] as its number. */
data class Status(val text: Int, val arg: Int? = null, val isError: Boolean = false, val plural: Boolean = false)

data class UiState(
    val loaded: Boolean = false,
    val units: List<UnitUi> = emptyList(),
    val selectedId: String? = null,
    val screen: Screen = Screen.UNIT,
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val pendingTemp: Double? = null,
    val onLocalNetwork: Boolean = true,
    /** The user allowed the app on the local network (always so before Android 17). */
    val networkAllowed: Boolean = true,
    val status: Status? = null,
) {
    val selected get() = units.firstOrNull { it.stored.airconId == selectedId } ?: units.firstOrNull()

    /** Unnamed units are numbered so they can be told apart until the user names them. */
    fun unnamedIndex(unit: UnitUi) = units.filter { it.stored.name == null }.indexOf(unit) + 1
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repository
    private val live = MutableStateFlow<Map<String, Result<AirconState>>>(emptyMap())
    private val ui = MutableStateFlow(UiState(networkAllowed = repo.networkAllowed))
    private var tempJob: Job? = null

    val state: StateFlow<UiState> = combine(repo.units, live, ui) { store, states, u ->
        u.copy(
            units = store.units.map { s ->
                val blocked = repo.needsNetworkPermission(s)
                val r = states[s.airconId].takeUnless { blocked }
                UnitUi(s, r?.getOrNull(), r?.exceptionOrNull()?.let(::errorText), needsPermission = blocked)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ui.value)

    private val app get() = getApplication<Application>()

    fun select(airconId: String) = ui.update { it.copy(selectedId = airconId, pendingTemp = null) }

    fun showManage(show: Boolean) = ui.update { it.copy(screen = if (show) Screen.MANAGE else Screen.UNIT, status = null) }

    suspend fun refresh() {
        ui.update { it.copy(onLocalNetwork = Discovery.onLocalNetwork(app)) }
        if (ui.value.busy) return
        val units = repo.units.value.units
        // Each unit shows as soon as it answers: one out of reach (which takes until its timeout)
        // must not keep the others waiting.
        units.map { u ->
            viewModelScope.async {
                val result = runCatching { repo.state(u.airconId) }
                live.update { it + (u.airconId to result) }
            }
        }.awaitAll()
        // The status line belongs to the last user action; polling leaves it alone.
        ui.update { it.copy(loaded = true) }
        viewModelScope.launch { repo.migrateAccounts() }
    }

    /** First start: find the units without being asked, once the app may use the network. */
    fun scanIfEmpty() {
        if (repo.units.value.units.isEmpty() && !ui.value.scanning && repo.networkAllowed) scan()
    }

    /**
     * The local network permission may have changed (asked for, or changed in Settings). When it
     * is newly granted, look for units at once: search if there are no real ones yet.
     */
    fun networkPermissionChanged() {
        val allowed = repo.networkAllowed
        if (allowed == ui.value.networkAllowed) return
        ui.update { it.copy(networkAllowed = allowed) }
        if (!allowed) return
        if (repo.units.value.units.all { it.demo }) {
            if (!ui.value.scanning) scan()
        } else {
            viewModelScope.launch { refresh() }
        }
    }

    fun scan() = viewModelScope.launch {
        ui.update { it.copy(scanning = true, status = Status(R.string.searching)) }
        try {
            val n = repo.scan()
            refresh()
            ui.update { it.copy(status = Status(R.plurals.search_result, n, plural = true)) }
        } catch (e: Exception) {
            fail(e)
        } finally {
            ui.update { it.copy(scanning = false) }
        }
    }

    fun add(host: String, done: () -> Unit) = viewModelScope.launch {
        ui.update { it.copy(busy = true, status = Status(R.string.searching)) }
        try {
            val unit = repo.add(host)
            ui.update { it.copy(busy = false, selectedId = unit.airconId, screen = Screen.UNIT, status = null) }
            refresh()
            done()
            repo.claim(unit.airconId)
        } catch (e: Exception) {
            ui.update { it.copy(busy = false) }
            fail(e)
        }
    }

    /** Adds the simulated unit and shows it, for trying the app without an air conditioner. */
    fun addDemo() = viewModelScope.launch {
        try {
            val unit = repo.addDemo(app.getString(R.string.demo_unit_name))
            refresh()
            ui.update { it.copy(selectedId = unit.airconId, screen = Screen.UNIT, status = null) }
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun rename(airconId: String, name: String) = viewModelScope.launch {
        try {
            repo.rename(airconId, name)
            // Naming a unit claims it: a unit without a free account slot says so now.
            repo.claim(airconId)
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun move(airconId: String, by: Int) = viewModelScope.launch { repo.move(airconId, by) }

    /** A plain-text report about a unit for the user to share; nothing personal in it. */
    fun diagnostics(airconId: String): String {
        val app = getApplication<Application>()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val state = live.value[airconId]?.getOrNull()
        return repo.diagnostics(airconId, version, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", state)
    }

    fun forget(airconId: String) = viewModelScope.launch {
        live.update { it - airconId }
        repo.forget(airconId)
    }

    private fun write(change: (AirconState) -> AirconState) {
        val id = state.value.selected?.stored?.airconId ?: return
        viewModelScope.launch {
            ui.update { it.copy(busy = true, status = null) }
            try {
                val next = repo.apply(id, onWait = { s -> ui.update { it.copy(status = Status(R.string.waiting_for_unit, s)) } }, change = change)
                live.update { it + (id to Result.success(next)) }
                ui.update { it.copy(status = null) }
            } catch (e: Exception) {
                fail(e)
            } finally {
                ui.update { it.copy(busy = false, pendingTemp = null) }
            }
        }
    }

    fun setPower(on: Boolean) = write { it.copy(power = on) }

    fun setMode(mode: Mode) = write { it.copy(mode = mode) }

    /** [airFlow] 0 is auto, 1–4 the manual steps. */
    fun setFanSpeed(airFlow: Int) = write { it.copy(airFlow = airFlow) }

    /** [windUD] 0 is swing, 1–4 a fixed position of the up/down vane, highest to lowest. Ends 3D auto. */
    fun setVane(windUD: Int) = write { it.copy(windUD = windUD, entrust = false) }

    /**
     * [windLR] 0 is swing, 1–5 a fixed position of the left/right vane from left to right, 6
     * spread out, 7 pointed inwards. Ends 3D auto.
     */
    fun setVaneLR(windLR: Int) = write { it.copy(windLR = windLR, entrust = false) }

    /** 3D auto: the unit aims both vanes itself. */
    fun setAuto3d() = write { it.copy(entrust = true) }

    /** The module wants to be addressed calmly: only send once the taps have stopped. */
    fun nudgeTemp(delta: Double) {
        val current = state.value.pendingTemp ?: state.value.selected?.state?.presetTemp ?: return
        val next = (current + delta).coerceIn(TEMP_MIN, TEMP_MAX)
        ui.update { it.copy(pendingTemp = next) }
        tempJob?.cancel()
        tempJob = viewModelScope.launch {
            delay(700)
            write { it.copy(presetTemp = next) }
        }
    }

    fun setTimer(minutes: Int) = viewModelScope.launch {
        val id = state.value.selected?.stored?.airconId ?: return@launch
        try {
            repo.setTimer(id, minutes)
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun cancelTimer() = viewModelScope.launch {
        state.value.selected?.stored?.airconId?.let { repo.cancelTimer(it) }
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "action failed", e)
        val busyFor = (e as? WfRacException)?.retryAfterS?.takeIf { e.code == WfRacException.UNIT_BUSY }
        ui.update { it.copy(status = Status(errorText(e), arg = busyFor, isError = true)) }
    }

    companion object {
        private const val TAG = "AirLAN"
        const val TEMP_MIN = 16.0
        const val TEMP_MAX = 30.0

        @StringRes
        fun errorText(e: Throwable): Int = when (e) {
            is NoUnitAtAddressException -> R.string.error_no_unit_at_address
            is LocalNetworkNotAllowedException -> R.string.permission_denied
            is WfRacException -> when (e.code) {
                WfRacException.UNIT_REFUSED -> R.string.error_unit_refused
                WfRacException.UNIT_BUSY -> if (e.retryAfterS != null) R.string.error_unit_busy else R.string.error_unit_refused
                WfRacException.NO_ACCOUNT_SLOT -> R.string.error_no_account_slot
                else -> R.string.error_bad_response
            }
            is IllegalArgumentException -> R.string.error_invalid
            is IOException -> R.string.error_unreachable
            else -> R.string.error_unreachable
        }
    }
}
