package io.github.erdtsieck.airlan.ui

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erdtsieck.airlan.R
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

/** A unit as shown: what is stored about it, plus its last known live state or why it is missing. */
data class UnitUi(val stored: StoredUnit, val state: AirconState?, @StringRes val error: Int?) {
    val online get() = state != null
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
    val status: Status? = null,
) {
    val selected get() = units.firstOrNull { it.stored.airconId == selectedId } ?: units.firstOrNull()

    /** Unnamed units are numbered so they can be told apart until the user names them. */
    fun unnamedIndex(unit: UnitUi) = units.filter { it.stored.name == null }.indexOf(unit) + 1
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.repository
    private val live = MutableStateFlow<Map<String, Result<AirconState>>>(emptyMap())
    private val ui = MutableStateFlow(UiState())
    private var tempJob: Job? = null

    val state: StateFlow<UiState> = combine(repo.units, live, ui) { store, states, u ->
        u.copy(
            units = store.units.map { s ->
                val r = states[s.airconId]
                UnitUi(s, r?.getOrNull(), r?.exceptionOrNull()?.let(::errorText))
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val app get() = getApplication<Application>()

    fun select(airconId: String) = ui.update { it.copy(selectedId = airconId, pendingTemp = null) }

    fun showManage(show: Boolean) = ui.update { it.copy(screen = if (show) Screen.MANAGE else Screen.UNIT, status = null) }

    suspend fun refresh() {
        ui.update { it.copy(onLocalNetwork = Discovery.onLocalNetwork(app)) }
        if (ui.value.busy) return
        val units = repo.units.value.units
        val results = units.map { u -> viewModelScope.async { u.airconId to runCatching { repo.state(u.airconId) } } }.awaitAll()
        live.update { old -> old + results }
        // The status line belongs to the last user action; polling leaves it alone.
        ui.update { it.copy(loaded = true) }
    }

    /** First start: find the units without being asked. */
    fun scanIfEmpty() {
        if (repo.units.value.units.isEmpty() && !ui.value.scanning) scan()
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
        } catch (e: Exception) {
            ui.update { it.copy(busy = false) }
            fail(e)
        }
    }

    fun rename(airconId: String, name: String) = viewModelScope.launch {
        try {
            repo.rename(airconId, name)
        } catch (e: Exception) {
            fail(e)
        }
    }

    fun forget(airconId: String) = viewModelScope.launch {
        repo.forget(airconId)
        live.update { it - airconId }
    }

    private fun write(change: (AirconState) -> AirconState) {
        val id = state.value.selected?.stored?.airconId ?: return
        viewModelScope.launch {
            ui.update { it.copy(busy = true, status = null) }
            try {
                val next = repo.apply(id, change)
                live.update { it + (id to Result.success(next)) }
            } catch (e: Exception) {
                fail(e)
            } finally {
                ui.update { it.copy(busy = false, pendingTemp = null) }
            }
        }
    }

    fun setPower(on: Boolean) = write { it.copy(power = on) }

    fun setMode(mode: Mode) = write { it.copy(mode = mode) }

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
        ui.update { it.copy(status = Status(errorText(e), isError = true)) }
    }

    companion object {
        private const val TAG = "AirLAN"
        const val TEMP_MIN = 16.0
        const val TEMP_MAX = 30.0

        @StringRes
        fun errorText(e: Throwable): Int = when (e) {
            is NoUnitAtAddressException -> R.string.error_no_unit_at_address
            is WfRacException -> if (e.code == "unit_refused") R.string.error_unit_refused else R.string.error_bad_response
            is IllegalArgumentException -> R.string.error_invalid
            is IOException -> R.string.error_unreachable
            else -> R.string.error_unreachable
        }
    }
}
