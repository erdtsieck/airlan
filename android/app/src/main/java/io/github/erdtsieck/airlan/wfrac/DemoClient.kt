package io.github.erdtsieck.airlan.wfrac

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A simulated air conditioner, so the app can be tried without one. Seen from the app it
 * behaves like a module: it refuses writes until an account is registered, every write goes
 * through the frame encoding a real unit receives, and it reports indoor and outdoor
 * temperatures, the indoor one moving towards the set temperature while it runs.
 *
 * The state lives in memory only: each time the process starts, the demo unit starts
 * switched off, cooling at 21 °C, like a unit that was just plugged in.
 */
class DemoClient(private val now: () -> Long = System::currentTimeMillis) : AirconClient {
    override val scheme: Scheme? get() = null
    override val certificatePin: String? get() = null

    private val mutex = Mutex()
    private var registered = false
    private var state = roundTrip(
        AirconState(
            power = false, mode = Mode.COOL, presetTemp = 21.0, airFlow = 0, windUD = 0, windLR = 0, entrust = false,
            coolHotJudge = true, modelNr = 0, modelNrRaw = 0, vacant = false, selfClean = false,
        ),
    )
    private var indoor = ROOM_TEMP
    private var indoorAt = now()

    override suspend fun getStatus(airconId: String): UnitStatus = mutex.withLock { UnitStatus(reported(), accounts = if (registered) 1 else 0) }

    override suspend fun setStat(airconId: String, state: AirconState): AirconState = mutex.withLock {
        if (!registered) throw WfRacException(WfRacException.UNIT_REFUSED, "unit refused setAirconStat (result 2)", 2)
        advanceIndoor() // up to now under the old state
        this.state = roundTrip(state)
        reported()
    }

    override suspend fun registerAccount(airconId: String, timezone: String) = mutex.withLock { registered = true }

    override suspend fun migrateAccount(airconId: String, legacyOperatorId: String, timezone: String) = registerAccount(airconId, timezone)

    private fun reported(): AirconState {
        advanceIndoor()
        return state.copy(indoorTemp = (indoor * 2).roundToLong() / 2.0, outdoorTemp = OUTDOOR_TEMP)
    }

    /** The room cools or warms towards the set temperature while the unit runs, and drifts back while it is off. */
    private fun advanceIndoor() {
        val t = now()
        val minutes = (t - indoorAt) / 60_000.0
        indoorAt = t
        if (minutes <= 0) return
        val s = state
        val (target, perMinute) = when {
            s.power && s.mode == Mode.COOL -> min(s.presetTemp, ROOM_TEMP) to RUNNING_RATE
            s.power && s.mode == Mode.HEAT -> max(s.presetTemp, ROOM_TEMP) to RUNNING_RATE
            // Auto cools or heats towards the setpoint, whichever way it lies.
            s.power && s.mode == Mode.AUTO -> s.presetTemp to RUNNING_RATE
            else -> ROOM_TEMP to IDLE_RATE
        }
        val step = min(abs(target - indoor), perMinute * minutes)
        indoor += if (target > indoor) step else -step
    }

    private companion object {
        const val ROOM_TEMP = 23.5
        const val OUTDOOR_TEMP = 14.5
        const val RUNNING_RATE = 0.2 // degrees per minute
        const val IDLE_RATE = 0.05

        /** What a module makes of a write: only what fits in a frame survives, and a state that cannot be encoded is refused. */
        fun roundTrip(s: AirconState) = Codec.decode(Codec.encode(s))
    }
}
