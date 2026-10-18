package io.github.erdtsieck.airlan.wfrac

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the same vectors as the Node implementation's test/codec.test.js. */
class CodecTest {
    private val fixtures = File(System.getProperty("airlan.fixtures") ?: error("airlan.fixtures not set"))
    private fun fixture(name: String) = JSONObject(File(fixtures, name).readText())

    private val live = fixture("live-frames.json").getJSONObject("frames")
    private val vectors = fixture("pywfrac-vectors.json")

    private fun JSONObject.toState() = AirconState(
        power = getBoolean("power"),
        mode = Mode.entries[getInt("mode")],
        presetTemp = getDouble("presetTemp"),
        airFlow = getInt("airFlow"),
        windUD = getInt("windUD"),
        windLR = getInt("windLR"),
        entrust = getBoolean("entrust"),
        coolHotJudge = getBoolean("coolHotJudge"),
        modelNr = getInt("modelNr"),
        modelNrRaw = getInt("modelNrRaw"),
        vacant = getBoolean("vacant"),
        selfClean = getBoolean("selfClean"),
    )

    @Test
    fun `crc16 is CRC-16 CCITT-FALSE`() {
        assertEquals(0x29b1, Codec.crc16("123456789".map { it.code }.toIntArray()))
    }

    @Test
    fun `decodes the captured live frames`() {
        val expected = mapOf(
            "e81656c09e49" to listOf(false, Mode.COOL, 21.0, 24.0, 17.7),
            "e81656cb0d45" to listOf(false, Mode.HEAT, 20.5, 24.0, 16.7),
            "e8165626b22e" to listOf(false, Mode.COOL, 23.0, 28.2, 11.7),
        )
        for ((id, values) in expected) {
            val s = Codec.decode(live.getJSONObject(id).getString("airconStat"))
            assertEquals(id, values, listOf(s.power, s.mode, s.presetTemp, s.indoorTemp, s.outdoorTemp))
        }
    }

    @Test
    fun `decodes every frame exactly like pywfrac`() {
        val decode = vectors.getJSONArray("decode")
        for (i in 0 until decode.length()) {
            val v = decode.getJSONObject(i)
            val frame = v.getString("frame")
            val ours = Codec.decode(frame)
            val expected = v.getJSONObject("state")
            val actual = mapOf(
                "power" to ours.power, "mode" to ours.mode?.ordinal, "presetTemp" to ours.presetTemp,
                "airFlow" to ours.airFlow, "windUD" to ours.windUD, "windLR" to ours.windLR,
                "entrust" to ours.entrust, "coolHotJudge" to ours.coolHotJudge, "modelNr" to ours.modelNr,
                "modelNrRaw" to ours.modelNrRaw, "vacant" to ours.vacant, "selfClean" to ours.selfClean,
                "compressorRunning" to ours.compressorRunning, "errorCode" to ours.errorCode,
                "indoorTemp" to ours.indoorTemp, "outdoorTemp" to ours.outdoorTemp,
            )
            for (field in expected.keys()) {
                val want = expected.get(field).let { if (it == JSONObject.NULL) null else it }
                val got = actual.getValue(field)
                val equal = if (want is Number && got is Number) want.toDouble() == got.toDouble() else want == got
                assertEquals("$field of $frame", true, equal)
            }
        }
    }

    @Test
    fun `encodes every state byte for byte like pywfrac`() {
        val encode = vectors.getJSONArray("encode")
        for (i in 0 until encode.length()) {
            val v = encode.getJSONObject(i)
            assertEquals(v.getJSONObject("state").toString(), v.getString("frame"), Codec.encode(v.getJSONObject("state").toState()))
        }
    }

    @Test
    fun `byte 12 pinned to 1 is the only reading that does not prove a reported left-right vane`() {
        for (id in live.keys()) assertTrue(Codec.decode(live.getJSONObject(id).getString("airconStat")).vaneLRReported)
        val base = Codec.decode(live.getJSONObject("e81656c09e49").getString("airconStat")).copy(modelNr = 0, modelNrRaw = 0, entrust = false)
        assertFalse(Codec.decode(Codec.encode(base.copy(windLR = 0))).vaneLRReported)
        assertTrue(Codec.decode(Codec.encode(base.copy(windLR = 3))).vaneLRReported)
        assertTrue(Codec.decode(Codec.encode(base.copy(entrust = true))).vaneLRReported)
        assertEquals(listOf(true, true, true, true, false), listOf(0, 1, 2, 3, 64).map(Codec::modelHasVaneLR))
    }

    /**
     * A tester saw nothing change while his unit was in auto. The frame is not the cause: a
     * write in auto carries every set-bit (mode, power, setpoint, fan, vane) like in any
     * other mode, and every mode reads back as written.
     */
    @Test
    fun `a write in any mode carries every set-bit and reads back as written`() {
        val base = Codec.decode(live.getJSONObject("e81656c09e49").getString("airconStat"))
        for (mode in Mode.entries) {
            val written = base.copy(power = true, mode = mode, presetTemp = 23.5, airFlow = 3, windUD = 2, entrust = false)
            val bytes = java.util.Base64.getDecoder().decode(Codec.encode(written)).map { it.toInt() and 0xff }
            assertEquals("$mode power set-bit", 0x02, bytes[2] and 0x02)
            assertEquals("$mode mode set-bit", 0x20, bytes[2] and 0x20)
            assertEquals("$mode setpoint set-bit", 0x80, bytes[4] and 0x80)
            assertEquals("$mode setpoint", 47, bytes[4] and 0x7f)
            assertEquals("$mode fan set-bit", 0x08, bytes[3] and 0x08)
            assertEquals("$mode vane set-bit", 0x80, bytes[3] and 0x80)
            val read = Codec.decode(Codec.encode(written))
            assertEquals(mode, read.mode)
            assertEquals(23.5, read.presetTemp, 0.0)
            assertEquals(3, read.airFlow)
            assertTrue(read.power)
        }
    }

    @Test
    fun `refuses states it cannot encode`() {
        val base = Codec.decode(live.getJSONObject("e81656c09e49").getString("airconStat"))
        assertThrows(IllegalArgumentException::class.java) { Codec.encode(base.copy(mode = null)) }
        assertThrows(IllegalArgumentException::class.java) { Codec.encode(base.copy(airFlow = -1)) }
    }

    @Test
    fun `a frame without temperature segments reports them as unknown`() {
        val frame = vectors.getJSONArray("encode").getJSONObject(0).getString("frame")
        assertNull(Codec.decode(frame).indoorTemp)
    }
}
