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

/**
 * A unit the user has (or the scan has) added. [name] is null until the user names it.
 * A [demo] unit is simulated in the app instead of reached at [host]; see [Repository.addDemo].
 */
data class StoredUnit(
    val airconId: String,
    val name: String?,
    val host: String,
    val scheme: Scheme? = null,
    val certificatePin: String? = null,
    /** The pending switch-off, if a timer is set. */
    val timer: OffTimer? = null,
    val demo: Boolean = false,
    /** Moved off [StoreState.legacyOperatorId] to the shared account; see [Repository.migrateAccounts]. */
    val accountMigrated: Boolean = false,
)

/**
 * The unit's MAC address as people see it printed (E8:16:56:C0:9E:49), to tell units apart
 * before they have names. A real unit's airconId is its MAC address; anything else is shown as is.
 */
val StoredUnit.macLabel: String
    get() = if (airconId.length == 12 && airconId.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        airconId.uppercase().chunked(2).joinToString(":")
    } else {
        airconId
    }

/**
 * A pending switch-off, retries included. This is the single source of truth for the unit's
 * alarm: Android can drop alarms (reboot, update, a revoked exact-alarm permission), so the
 * alarm is always derived from this, never the other way round.
 */
data class OffTimer(
    /** Epoch millis at which the user wants the unit off. */
    val at: Long,
    /** Attempts to switch the unit off that have failed so far. */
    val failedAttempts: Int = 0,
    /** Epoch millis of the next attempt after a failure; null before the first attempt. */
    val retryAt: Long? = null,
) {
    /** When the alarm should go off next. */
    val dueAt get() = retryAt ?: at
}

/**
 * [deviceId] is this installation's own: the unit's write lock tells writers apart by it. The
 * account is AirLAN's shared one (AIRLAN_OPERATOR_ID); [legacyOperatorId] is the account of
 * its own that AirLAN 0.1.1 and older registered, null on installations that never had one.
 */
data class StoreState(val deviceId: String, val legacyOperatorId: String?, val units: List<StoredUnit>)

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
        if (!file.isFile) return StoreState("airlan-${UUID.randomUUID().toString().take(8)}", null, emptyList())
        val json = JSONObject(file.readText())
        val units = json.getJSONArray("units")
        return StoreState(
            deviceId = json.getString("deviceId"),
            // 0.1.1 and older stored their own account as "operatorId".
            legacyOperatorId = (json.optString("legacyOperatorId").ifEmpty { json.optString("operatorId") }).ifEmpty { null },
            units = (0 until units.length()).map { i ->
                val u = units.getJSONObject(i)
                StoredUnit(
                    airconId = u.getString("airconId"),
                    name = u.optString("name").takeIf { u.has("name") && !u.isNull("name") },
                    host = u.getString("host"),
                    scheme = u.optString("scheme").takeIf { it.isNotEmpty() }?.let(Scheme::valueOf),
                    certificatePin = u.optString("certificatePin").takeIf { it.isNotEmpty() },
                    timer = if (u.has("offAt")) {
                        OffTimer(
                            at = u.getLong("offAt"),
                            failedAttempts = u.optInt("offFailedAttempts", 0),
                            retryAt = if (u.has("offRetryAt")) u.getLong("offRetryAt") else null,
                        )
                    } else {
                        null
                    },
                    demo = u.optBoolean("demo", false),
                    accountMigrated = u.optBoolean("accountMigrated", false),
                )
            },
        )
    }

    private fun write(state: StoreState) {
        val json = JSONObject()
            .put("deviceId", state.deviceId)
            .apply { state.legacyOperatorId?.let { put("legacyOperatorId", it) } }
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
                                u.timer?.let { t ->
                                    put("offAt", t.at)
                                    if (t.failedAttempts > 0) put("offFailedAttempts", t.failedAttempts)
                                    t.retryAt?.let { put("offRetryAt", it) }
                                }
                                if (u.demo) put("demo", true)
                                if (u.accountMigrated) put("accountMigrated", true)
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
