package io.github.erdtsieck.airlan.wfrac

/**
 * The last requests to one unit and what the unit says about itself, kept in memory for a
 * diagnostics report. Nothing personal goes in: commands, timings, result codes and the
 * firmware the unit reports.
 */
class RequestTrace(private val capacity: Int = 30) {
    /** [result] is the unit's result code; null with [error] when no answer came back. */
    data class Entry(val atMs: Long, val command: String, val durationMs: Long, val result: Int?, val error: String?)

    /** What getAirconStat tells about the module besides the state. */
    data class Module(val firmType: String?, val wireless: String?, val mcu: String?, val accounts: Int?)

    private val entries = ArrayDeque<Entry>()

    @Volatile
    var module: Module? = null

    @Synchronized
    fun add(entry: Entry) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun entries(): List<Entry> = entries.toList()
}
