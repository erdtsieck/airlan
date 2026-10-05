package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.wfrac.Scheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** A unit the user has (or the scan has) added. [name] is null until the user names it. */
data class StoredUnit(
    val airconId: String,
    val name: String?,
    val host: String,
    val scheme: Scheme? = null,
    val certificatePin: String? = null,
    /** Epoch millis at which the unit should switch off, if a timer is set. */
    val offAt: Long? = null,
)

data class StoreState(val deviceId: String, val operatorId: String, val units: List<StoredUnit>)

/** The app's persistent state, as one JSON file written atomically. */
class UnitStore(private val file: File) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    fun unit(airconId: String): StoredUnit? = _state.value.units.firstOrNull { it.airconId == airconId }

    suspend fun update(transform: (StoreState) -> StoreState): StoreState = mutex.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            write(next)
            _state.value = next
        }
        next
    }

    suspend fun updateUnit(airconId: String, transform: (StoredUnit) -> StoredUnit) =
        update { s -> s.copy(units = s.units.map { if (it.airconId == airconId) transform(it) else it }) }

    private fun load(): StoreState {
        if (!file.isFile) return StoreState("airlan-${UUID.randomUUID().toString().take(8)}", UUID.randomUUID().toString(), emptyList())
        val json = JSONObject(file.readText())
        val units = json.getJSONArray("units")
        return StoreState(
            deviceId = json.getString("deviceId"),
            operatorId = json.getString("operatorId"),
            units = (0 until units.length()).map { i ->
                val u = units.getJSONObject(i)
                StoredUnit(
                    airconId = u.getString("airconId"),
                    name = u.optString("name").takeIf { u.has("name") && !u.isNull("name") },
                    host = u.getString("host"),
                    scheme = u.optString("scheme").takeIf { it.isNotEmpty() }?.let(Scheme::valueOf),
                    certificatePin = u.optString("certificatePin").takeIf { it.isNotEmpty() },
                    offAt = if (u.has("offAt")) u.getLong("offAt") else null,
                )
            },
        )
    }

    private fun write(state: StoreState) {
        val json = JSONObject()
            .put("deviceId", state.deviceId)
            .put("operatorId", state.operatorId)
            .put(
                "units",
                JSONArray(
                    state.units.map { u ->
                        JSONObject()
                            .put("airconId", u.airconId)
                            .put("name", u.name ?: JSONObject.NULL)
                            .put("host", u.host)
                            .apply {
                                u.scheme?.let { put("scheme", it.name) }
                                u.certificatePin?.let { put("certificatePin", it) }
                                u.offAt?.let { put("offAt", it) }
                            }
                    },
                ),
            )
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not write ${file.path}" }
        }
    }
}
