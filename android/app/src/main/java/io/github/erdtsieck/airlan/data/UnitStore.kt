package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.Codec
import io.github.erdtsieck.airlan.wfrac.Mode
import io.github.erdtsieck.airlan.wfrac.Scheme
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    /** The pending switch-off timer. */
    val offTimer: UnitTimer? = null,
    val schedule: Schedule = Schedule(),
    val demo: Boolean = false,
    /** Moved off [StoreState.legacyOperatorId] to the shared account; see [Repository.migrateAccounts]. */
    val accountMigrated: Boolean = false,
    /** The model group byte, once read; see [aimsBothVanes]. */
    val modelNrRaw: Int? = null,
    /** Once true, the unit has shown that it reports its left/right vane; see [AirconState.vaneLRReported]. */
    val reportsVaneLR: Boolean = false,
)

/**
 * Whether the unit gets the left/right vane and 3D auto: its model line has them and it has
 * shown that it reports them back. A unit that never does would always read back as
 * swinging, whatever was chosen. The demo unit has everything.
 */
val StoredUnit.aimsBothVanes: Boolean
    get() = demo || (reportsVaneLR && modelNrRaw?.let(Codec::modelHasVaneLR) == true)

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
 * [deviceId] is this installation's own: the unit's write lock tells writers apart by it. The
 * account is AirLAN's shared one (AIRLAN_OPERATOR_ID); [legacyOperatorId] is the account of
 * its own that AirLAN 0.1.1 and older registered, null on installations that never had one.
 */
data class StoreState(val deviceId: String, val legacyOperatorId: String?, val units: List<StoredUnit>)

/**
 * The app's persistent state, as one JSON file written atomically. [zone] places the clock
 * times of the switch-on timer of AirLAN 0.4, which loads as a one-time schedule rule.
 */
class UnitStore(private val file: File, private val zone: () -> ZoneId = ZoneId::systemDefault) {
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
                    offTimer = timerOf(u, "off"),
                    schedule = scheduleOf(u),
                    demo = u.optBoolean("demo", false),
                    accountMigrated = u.optBoolean("accountMigrated", false),
                    modelNrRaw = if (u.has("modelNrRaw")) u.getInt("modelNrRaw") else null,
                    reportsVaneLR = u.optBoolean("reportsVaneLR", false),
                )
            },
        )
    }

    /** "offAt", "offFailedAttempts", "offRetryAt"; AirLAN 0.4 stored its switch-on timer the same way as "on". */
    private fun timerOf(u: JSONObject, key: String): UnitTimer? {
        if (!u.has("${key}At")) return null
        return UnitTimer(
            at = u.getLong("${key}At"),
            failedAttempts = u.optInt("${key}FailedAttempts", 0),
            retryAt = if (u.has("${key}RetryAt")) u.getLong("${key}RetryAt") else null,
        )
    }

    private fun scheduleOf(u: JSONObject): Schedule {
        val json = u.optJSONObject("schedule") ?: return legacyOnTimer(u)
        val rules = json.optJSONArray("rules") ?: JSONArray()
        return Schedule(
            rules = (0 until rules.length()).map { i ->
                val r = rules.getJSONObject(i)
                val days = r.optJSONArray("days") ?: JSONArray()
                ScheduleRule(
                    id = r.getString("id"),
                    action = TimerAction.entries.first { it.key == r.getString("action") },
                    minuteOfDay = r.getInt("minuteOfDay"),
                    days = (0 until days.length()).map { DayOfWeek.valueOf(days.getString(it)) }.toSet(),
                    setting = if (r.has("mode")) Setting(Mode.valueOf(r.getString("mode")), r.getDouble("temp")) else null,
                    enabled = r.optBoolean("enabled", true),
                )
            },
            pausedUntil = json.optString("pausedUntil").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
            skipAt = if (json.has("skipAt")) json.getLong("skipAt") else null,
            run = json.optJSONObject("run")?.let { r ->
                ScheduleRun(
                    ruleId = r.getString("ruleId"),
                    at = r.getLong("at"),
                    failedAttempts = r.optInt("failedAttempts", 0),
                    retryAt = if (r.has("retryAt")) r.getLong("retryAt") else null,
                    failedAtHome = r.optBoolean("failedAtHome", false),
                )
            },
            last = json.optJSONObject("last")?.let { r ->
                ScheduleOutcome(
                    action = TimerAction.entries.first { it.key == r.getString("action") },
                    at = r.getLong("at"),
                    doneAt = if (r.has("doneAt")) r.getLong("doneAt") else null,
                )
            },
        )
    }

    /**
     * AirLAN 0.4's switch-on timer ("onAt") is a one-time rule at its clock time, pending with
     * the attempts it had made.
     */
    private fun legacyOnTimer(u: JSONObject): Schedule {
        val timer = timerOf(u, "on") ?: return Schedule()
        val time = Instant.ofEpochMilli(timer.at).atZone(zone())
        val rule = ScheduleRule(LEGACY_ON_RULE_ID, TimerAction.ON, time.hour * 60 + time.minute, days = emptySet())
        return Schedule(
            rules = listOf(rule),
            run = ScheduleRun(rule.id, timer.at, timer.failedAttempts, timer.retryAt),
        )
    }

    private fun scheduleJson(s: Schedule): JSONObject = JSONObject()
        .put(
            "rules",
            JSONArray(
                s.rules.map { r ->
                    JSONObject()
                        .put("id", r.id)
                        .put("action", r.action.key)
                        .put("minuteOfDay", r.minuteOfDay)
                        .put("days", JSONArray(r.days.sorted().map { it.name }))
                        .apply {
                            r.setting?.let { put("mode", it.mode.name).put("temp", it.presetTemp) }
                            if (!r.enabled) put("enabled", false)
                        }
                },
            ),
        )
        .apply {
            s.pausedUntil?.let { put("pausedUntil", it.toString()) }
            s.skipAt?.let { put("skipAt", it) }
            s.run?.let { r ->
                put(
                    "run",
                    JSONObject().put("ruleId", r.ruleId).put("at", r.at).apply {
                        if (r.failedAttempts > 0) put("failedAttempts", r.failedAttempts)
                        r.retryAt?.let { put("retryAt", it) }
                        if (r.failedAtHome) put("failedAtHome", true)
                    },
                )
            }
            s.last?.let { o ->
                put("last", JSONObject().put("action", o.action.key).put("at", o.at).apply { o.doneAt?.let { put("doneAt", it) } })
            }
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
                                u.offTimer?.let { t ->
                                    put("offAt", t.at)
                                    if (t.failedAttempts > 0) put("offFailedAttempts", t.failedAttempts)
                                    t.retryAt?.let { put("offRetryAt", it) }
                                }
                                if (u.schedule != Schedule()) put("schedule", scheduleJson(u.schedule))
                                if (u.demo) put("demo", true)
                                if (u.accountMigrated) put("accountMigrated", true)
                                u.modelNrRaw?.let { put("modelNrRaw", it) }
                                if (u.reportsVaneLR) put("reportsVaneLR", true)
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

/** The id of the one-time rule that AirLAN 0.4's switch-on timer becomes. */
const val LEGACY_ON_RULE_ID = "on-timer"
