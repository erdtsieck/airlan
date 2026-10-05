package io.github.erdtsieck.airlan.wfrac

import java.io.IOException
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
    private val operatorId: String,
    var scheme: Scheme? = null,
    var certificatePin: String? = null,
    private val port: Int = WFRAC_PORT,
    private val minIntervalMs: Long = 1100,
    private val timeoutMs: Int = 6000,
) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    private fun send(scheme: Scheme, command: String, payload: String): JSONObject {
        val response = post(scheme, host, port, "/beaver/command/$command", payload, timeoutMs, certificatePin)
        response.certificatePin?.let { certificatePin = it }
        return try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw WfRacException("bad_response", "unexpected reply (HTTP ${response.status}): ${response.body.take(80)}")
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

    suspend fun request(command: String, contents: Map<String, Any>?): JSONObject = mutex.withLock {
        val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            val payload = buildMap {
                put("apiVer", "1.0")
                put("command", command)
                put("deviceId", deviceId)
                put("operatorId", operatorId)
                put("timestamp", System.currentTimeMillis() / 1000)
                if (contents != null) put("contents", contents)
            }
            val body = withContext(Dispatchers.IO) { sendDiscovering(command, JsonWriter.write(payload)) }
            val result = body.optInt("result", -1)
            if (result != 0) throw WfRacException("unit_refused", "unit refused $command (result $result)", result)
            body.optJSONObject("contents") ?: JSONObject()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    suspend fun getDeviceInfo(): String = request("getDeviceInfo", emptyMap()).getString("airconId")

    suspend fun getStat(airconId: String): AirconState =
        Codec.decode(request("getAirconStat", mapOf("airconId" to airconId)).getString("airconStat"))

    suspend fun setStat(airconId: String, state: AirconState): AirconState =
        Codec.decode(
            request("setAirconStat", mapOf("airconId" to airconId, "airconStat" to Codec.encode(state))).getString("airconStat"),
        )

    /** Writes require a registered account (4 slots per unit). Re-registering the same id is idempotent. */
    suspend fun registerAccount(airconId: String, timezone: String) {
        request("updateAccountInfo", mapOf("accountId" to operatorId, "airconId" to airconId, "remote" to 0, "timezone" to timezone))
    }
}
