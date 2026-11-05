package io.github.erdtsieck.airlan.wfrac

/**
 * The last requests to one unit and what the unit says about itself, kept in memory for a
 * diagnostics report. Nothing personal goes in: commands, timings, result codes and the
 * firmware the unit reports.
 */
class RequestTrace(private val capacity: Int = 30) {
    /**
     * [result] is the unit's result code; null with [error] when no answer came back.
     * [waitedMs] is how long the request queued behind others to the same unit (and the
     * spacing the module wants) before it went out.
     */
    data class Entry(val atMs: Long, val command: String, val durationMs: Long, val result: Int?, val error: String?, val waitedMs: Long = 0)

    /** Something that happened to the unit besides a request: it moved, the network was searched. */
    data class Note(val atMs: Long, val text: String)

    /** What getAirconStat tells about the module besides the state. */
    data class Module(val firmType: String?, val wireless: String?, val mcu: String?, val accounts: Int?)

    private val entries = ArrayDeque<Entry>()
    private val notes = ArrayDeque<Note>()

    @Volatile
    var module: Module? = null

    @Synchronized
    fun add(entry: Entry) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    @Synchronized
    fun note(text: String, atMs: Long = System.currentTimeMillis()) {
        if (notes.size == capacity) notes.removeFirst()
        notes.addLast(Note(atMs, text))
    }

    @Synchronized
    fun notes(): List<Note> = notes.toList()
}
