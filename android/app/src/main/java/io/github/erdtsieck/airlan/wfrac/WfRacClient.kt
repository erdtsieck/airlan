package io.github.erdtsieck.airlan.wfrac

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val WFRAC_PORT = 51443

/**
 * Talks to one module. The module handles one connection at a time and wants about a
 * second between requests, so requests are serialised and spaced out.
 *
 * The scheme is discovered on first contact and exposed as [scheme] (and the HTTPS
 * certificate pin as [certificatePin]) so the caller can persist them. A transport failure
 * on a known scheme forgets it, so a firmware update that switches protocol is picked up.
 */
class WfRacClient(
    val host: String,
    private val deviceId: String,
    override var scheme: Scheme? = null,
    override var certificatePin: String? = null,
    private val port: Int = WFRAC_PORT,
    private val minIntervalMs: Long = 1100,
    private val timeoutMs: Int = 6000,
    private val now: () -> Long = System::currentTimeMillis,
    private val operatorId: String = AIRLAN_OPERATOR_ID,
    /** Where requests are recorded for a diagnostics report; shared across clients of one unit. */
    val trace: RequestTrace = RequestTrace(),
) : AirconClient {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    /** The `expires` our own last write set, per unit: a lock we hold does not keep us out. */
    private val ownLockUntil = ConcurrentHashMap<String, Long>()

    private fun nowS() = now() / 1000

    private fun send(scheme: Scheme, command: String, payload: String): JSONObject {
        val response = post(scheme, host, port, "/beaver/command/$command", payload, timeoutMs, certificatePin)
        response.certificatePin?.let { certificatePin = it }
        return try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw WfRacException(WfRacException.BAD_RESPONSE, "unexpected reply (HTTP ${response.status}): ${response.body.take(80)}")
        }
    }

    private fun sendDiscovering(command: String, payload: String): JSONObject {
        scheme?.let { known ->
            try {
                return send(known, command, payload)
            } catch (e: IOException) {
                scheme = null
                throw e
            }
        }
        var lastError: Exception? = null
        for (candidate in Scheme.entries) {
            try {
                return send(candidate, command, payload).also { scheme = candidate }
            } catch (e: IOException) {
                lastError = e
            } catch (e: WfRacException) {
                lastError = e
            }
        }
        throw lastError!!
    }

    suspend fun request(command: String, contents: Map<String, Any>?, asOperator: String = operatorId): JSONObject = mutex.withLock {
        val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val started = System.currentTimeMillis()
        var result: Int? = null
        var error: String? = null
        try {
            val payload = buildMap {
                put("apiVer", "1.0")
                put("command", command)
                put("deviceId", deviceId)
                put("operatorId", asOperator)
                // The module has no clock of its own and takes this as the time.
                put("timestamp", nowS())
                if (contents != null) put("contents", contents)
            }
            val body = withContext(Dispatchers.IO) { sendDiscovering(command, JsonWriter.write(payload)) }
            val code = body.optInt("result", -1)
            result = code
            if (code != 0) throw WfRacException(WfRacException.UNIT_REFUSED, "unit refused $command (result $code)", code)
            body.optJSONObject("contents") ?: JSONObject()
        } catch (e: Exception) {
            if (result == null) error = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
            throw e
        } finally {
            lastRequestAt = System.currentTimeMillis()
            trace.add(RequestTrace.Entry(started, command, lastRequestAt - started, result, error))
        }
    }

    suspend fun getDeviceInfo(): String = request("getDeviceInfo", emptyMap()).getString("airconId")

    override suspend fun getStatus(airconId: String): UnitStatus {
        val contents = request("getAirconStat", mapOf("airconId" to airconId))
        trace.module = RequestTrace.Module(
            firmType = contents.optString("firmType").ifEmpty { null },
            wireless = contents.optJSONObject("wireless")?.optString("firmVer")?.ifEmpty { null },
            mcu = contents.optJSONObject("mcu")?.optString("firmVer")?.ifEmpty { null },
            accounts = if (contents.has("numOfAccount")) contents.optInt("numOfAccount") else null,
        )
        return UnitStatus(
            Codec.decode(contents.getString("airconStat")),
            lockedForS = lockedForS(airconId, contents),
            accounts = if (contents.has("numOfAccount")) contents.optInt("numOfAccount") else null,
        )
    }

    /**
     * `expires` is when the last writer's lock lapses, on the module's clock, which every
     * request sets from its timestamp; so it compares with ours directly. A lock our own last
     * write set does not count: the unit lets its holder through.
     */
    private fun lockedForS(airconId: String, contents: JSONObject): Int {
        if (!contents.has("expires")) return 0
        val expires = contents.optLong("expires")
        val own = ownLockUntil[airconId]
        if (own != null && abs(expires - own) <= 2) return 0
        return (expires - nowS()).coerceAtLeast(0).toInt()
    }

    override suspend fun setStat(airconId: String, state: AirconState): AirconState {
        val contents = request("setAirconStat", mapOf("airconId" to airconId, "airconStat" to Codec.encode(state)))
        ownLockUntil[airconId] = nowS() + WRITE_LOCK_S
        return Codec.decode(contents.getString("airconStat"))
    }

    /** A full account table is told apart from other refusals by the unit's account count. */
    override suspend fun registerAccount(airconId: String, timezone: String) {
        try {
            request("updateAccountInfo", mapOf("accountId" to operatorId, "airconId" to airconId, "remote" to 0, "timezone" to timezone))
        } catch (e: WfRacException) {
            if (e.code != WfRacException.UNIT_REFUSED) throw e
            val accounts = runCatching { getStatus(airconId).accounts }.getOrNull()
            if (accounts == null || accounts >= ACCOUNT_SLOTS) {
                throw WfRacException(
                    WfRacException.NO_ACCOUNT_SLOT,
                    "unit $airconId has no free account slot (${accounts ?: "?"} of $ACCOUNT_SLOTS taken)",
                    e.result,
                )
            }
            throw e
        }
    }

    /**
     * Frees the old account first, sending that request as the old account, which makes room on
     * a full unit; then registers the shared one. The unit answers result 1 for an account it
     * does not hold: that slot is free already.
     */
    override suspend fun migrateAccount(airconId: String, legacyOperatorId: String, timezone: String) {
        if (legacyOperatorId != operatorId) {
            try {
                request("deleteAccountInfo", mapOf("accountId" to legacyOperatorId, "airconId" to airconId), asOperator = legacyOperatorId)
            } catch (e: WfRacException) {
                if (!(e.code == WfRacException.UNIT_REFUSED && e.result == 1)) throw e
            }
        }
        registerAccount(airconId, timezone)
    }
}
