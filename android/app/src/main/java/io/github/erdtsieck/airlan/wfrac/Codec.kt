package io.github.erdtsieck.airlan.wfrac

import java.util.Base64

// The airconStat frame of the WF-RAC module: a COMMAND block and a RECEIVE block, each
// 18 bytes + variable segments + CRC16. A write is always a full state frame: fields
// without their set-bit are ignored, so the current state is sent back with only the
// desired change applied. Port of pywfrac's parser.py (MIT, see NOTICE), the same as
// wfrac.js in the server; both are tested against the same vectors.

enum class Mode(internal val command: Int, internal val receive: Int) {
    AUTO(0x20, 0x00), COOL(0x28, 0x08), HEAT(0x30, 0x10), FAN(0x2c, 0x0c), DRY(0x24, 0x04);
}

/** Everything a frame carries that a write has to send back unchanged. */
data class AirconState(
    val power: Boolean,
    /** Null when the unit reports a mode this code does not know; such a state cannot be written. */
    val mode: Mode?,
    val presetTemp: Double,
    val airFlow: Int,
    val windUD: Int,
    val windLR: Int,
    val entrust: Boolean,
    val coolHotJudge: Boolean,
    val modelNr: Int,
    val modelNrRaw: Int,
    val vacant: Boolean,
    val selfClean: Boolean,
    val compressorRunning: Boolean = false,
    val errorCode: String? = null,
    val indoorTemp: Double? = null,
    val outdoorTemp: Double? = null,
)

object Codec {
    private val CMD_AIRFLOW = intArrayOf(0x0f, 0x08, 0x09, 0x0a, 0x0e)
    private val RCV_AIRFLOW = intArrayOf(0x07, 0x00, 0x01, 0x02, 0x06)
    private val CMD_WIND_UD = arrayOf(192 to 128, 128 to 128, 128 to 144, 128 to 160, 128 to 176)
    private val CMD_WIND_LR = arrayOf(3 to 16, 2 to 16, 2 to 17, 2 to 18, 2 to 19, 2 to 20, 2 to 21, 2 to 22)
    private val NO_SEGMENTS = intArrayOf(1, 255, 255, 255, 255)

    private fun emptyBlock() = intArrayOf(0, 0, 0, 0, 0, 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    /** CRC16-CCITT (poly 0x1021, init 0xFFFF), appended little-endian to each block. */
    fun crc16(bytes: IntArray): Int {
        var crc = 0xffff
        for (b in bytes) {
            crc = crc xor (b shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }

    private fun withCrc(bytes: IntArray): IntArray {
        val crc = crc16(bytes)
        return bytes + intArrayOf(crc and 0xff, crc shr 8)
    }

    fun decode(base64: String): AirconState {
        val d = Base64.getDecoder().decode(base64).map { it.toInt() and 0xff }
        val start = d[18] * 4 + 21
        val c = d.subList(start, start + 18)
        val segments = d.subList(start + 19, d.size - 2)

        val modelNrRaw = c[0] and 0x7f
        val modelNr = if (modelNrRaw == 3) 2 else listOf(0, 1, 2).indexOf(modelNrRaw)
        var indoor: Double? = null
        var outdoor: Double? = null
        var i = 0
        while (i + 3 < segments.size) {
            if (segments[i] == 0x80) {
                if (segments[i + 1] == 0x10) outdoor = TempTables.outdoor[segments[i + 2]]
                if (segments[i + 1] == 0x20) indoor = TempTables.indoor[segments[i + 2]]
            }
            i += 4
        }
        val error = c[6] and 0x7f
        return AirconState(
            power = (c[2] and 0x03) == 1,
            mode = Mode.entries.firstOrNull { it.receive == (c[2] and 0x3c) },
            presetTemp = c[4] / 2.0,
            airFlow = RCV_AIRFLOW.indexOf(c[3] and 0x0f),
            windUD = if ((c[2] and 0xc0) == 0x40) 0 else listOf(0, 16, 32, 48).indexOf(c[3] and 0xf0) + 1,
            windLR = if ((c[12] and 0x03) == 1) 0 else (0..6).indexOf(c[11] and 0x1f) + 1,
            entrust = (c[12] and 0x0c) == 4,
            coolHotJudge = (c[8] and 0x08) == 0,
            modelNr = modelNr,
            modelNrRaw = modelNrRaw,
            vacant = (c[10] and 1) != 0,
            selfClean = if (modelNr == 1 || modelNr == 2) (c[15] and 1) != 0 else false,
            compressorRunning = (c[9] and 0x02) != 0,
            errorCode = when {
                c[6] and 0x80 != 0 -> "M%02d".format(error)
                error == 0 -> null
                else -> "E$error"
            },
            indoorTemp = indoor,
            outdoorTemp = outdoor,
        )
    }

    private fun commandBlock(s: AirconState, mode: Mode): IntArray {
        val b = emptyBlock()
        b[2] = b[2] or (if (s.power) 3 else 2) or mode.command
        b[3] = b[3] or CMD_AIRFLOW[s.airFlow]
        CMD_WIND_UD.getOrNull(s.windUD)?.let { (b2, b3) -> b[2] = b[2] or b2; b[3] = b[3] or b3 }
        CMD_WIND_LR.getOrNull(s.windLR)?.let { (b12, b11) -> b[12] = b[12] or b12; b[11] = b[11] or b11 }
        b[4] = b[4] or ((s.presetTemp / 0.5).toInt() + 128)
        b[12] = b[12] or (if (s.entrust) 12 else 8)
        if (!s.coolHotJudge) b[8] = b[8] or 8
        if (s.modelNr == 1 && s.vacant) b[10] = b[10] or 1
        if (s.modelNr == 1 || s.modelNr == 2) b[12] = b[12] or (if (s.selfClean) 144 else 128)
        return b
    }

    private fun receiveBlock(s: AirconState, mode: Mode): IntArray {
        val b = emptyBlock()
        if (s.power) b[2] = b[2] or 1
        b[2] = b[2] or mode.receive
        b[3] = b[3] or RCV_AIRFLOW[s.airFlow]
        when (s.windUD) {
            0 -> b[2] = b[2] or 64
            2, 3, 4 -> b[3] = b[3] or ((s.windUD - 1) * 16)
        }
        when (s.windLR) {
            0 -> b[12] = b[12] or 1
            in 1..7 -> b[11] = b[11] or (s.windLR - 1)
        }
        b[4] = b[4] or (s.presetTemp / 0.5).toInt()
        if (s.entrust) b[12] = b[12] or 4
        if (!s.coolHotJudge) b[8] = b[8] or 8
        b[0] = b[0] or s.modelNrRaw
        if (s.modelNr == 1 && s.vacant) b[10] = b[10] or 1
        if (s.modelNr == 1 || s.modelNr == 2) b[12] = b[12] or (if (s.selfClean) 144 else 128)
        return b
    }

    fun encode(s: AirconState): String {
        val mode = requireNotNull(s.mode) { "unknown mode" }
        require(s.airFlow in CMD_AIRFLOW.indices) { "unknown fan speed ${s.airFlow}" }
        val bytes = withCrc(commandBlock(s, mode) + NO_SEGMENTS) + withCrc(receiveBlock(s, mode) + NO_SEGMENTS)
        return Base64.getEncoder().encodeToString(ByteArray(bytes.size) { bytes[it].toByte() })
    }
}
