package io.github.erdtsieck.airlan.wfrac

/**
 * Writes request bodies for the module. Not org.json: Android's org.json escapes every "/"
 * as "\/", which is valid JSON but which the module's parser does not undo. The base64
 * airconStat is full of slashes, so the unit then receives a garbled frame and refuses the
 * write (result 2). This writer escapes only what JSON requires.
 */
internal object JsonWriter {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    appendString(out, k as String)
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            else -> throw IllegalArgumentException("cannot write ${value::class.simpleName} as JSON")
        }
    }

    private fun appendString(out: StringBuilder, s: String) {
        out.append('"')
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch < ' ' -> out.append("\\u%04x".format(ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}
