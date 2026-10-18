package io.github.erdtsieck.airlan.data

import io.github.erdtsieck.airlan.wfrac.AirconState
import io.github.erdtsieck.airlan.wfrac.RequestTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text report about one unit for the user to send to the developer: app and Android
 * version, the module's firmware and transport, and the last requests with their outcome.
 * Nothing that identifies the user goes in: no names, addresses only by their last number,
 * the MAC only by its last four digits.
 */
object Diagnostics {
    private val IPV4 = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.(\d{1,3})\b""")

    /** An IPv4 address keeps only its last number: 192.168.1.23 becomes x.x.x.23. */
    fun maskAddresses(text: String) = IPV4.replace(text) { "x.x.x." + it.groupValues[1] }

    fun report(
        appVersion: String,
        androidVersion: String,
        unit: StoredUnit,
        state: AirconState?,
        trace: RequestTrace?,
        nowMs: Long,
    ): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        appendLine("AirLAN diagnostics, ${time.format(Date(nowMs))}")
        appendLine("App $appVersion, Android $androidVersion")
        appendLine()
        if (unit.demo) {
            appendLine("Unit: demo (simulated)")
        } else {
            appendLine("Unit: ...${unit.airconId.takeLast(4)} at ${maskAddresses(unit.host)}, transport ${unit.scheme?.name?.lowercase() ?: "unknown"}")
            val m = trace?.module
            appendLine("Module: firmType ${m?.firmType ?: "?"}, wireless ${m?.wireless ?: "?"}, mcu ${m?.mcu ?: "?"}, accounts ${m?.accounts ?: "?"}")
        }
        appendLine("Model group: ${state?.modelNrRaw ?: unit.modelNrRaw ?: "?"}, left/right vane reported: ${unit.reportsVaneLR}")
        if (state != null) {
            appendLine(
                "State: power ${if (state.power) "on" else "off"}, mode ${state.mode?.name?.lowercase() ?: "unknown"}, " +
                    "set ${state.presetTemp}, fan ${state.airFlow}, up/down ${state.windUD}, left/right ${state.windLR}, " +
                    "3D ${state.entrust}, error ${state.errorCode ?: "none"}",
            )
        } else {
            appendLine("State: not read")
        }
        val entries = trace?.entries().orEmpty()
        appendLine()
        appendLine("Last ${entries.size} requests:")
        val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        for (e in entries) {
            val outcome = when {
                e.result != null -> "result ${e.result}"
                else -> maskAddresses(e.error ?: "no answer")
            }
            appendLine("${clock.format(Date(e.atMs))} ${e.command} ${e.durationMs} ms $outcome")
        }
        val answered = entries.filter { it.result != null }
        if (answered.isNotEmpty()) {
            appendLine("Average answer time: ${answered.map { it.durationMs }.average().toLong()} ms, " +
                "failures: ${entries.count { it.result != 0 }} of ${entries.size}")
        }
    }
}
