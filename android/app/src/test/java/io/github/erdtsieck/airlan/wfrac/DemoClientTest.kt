package io.github.erdtsieck.airlan.wfrac

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DemoClientTest {
    private var clock = 1_000_000L
    private val demo = DemoClient { clock }

    @Test
    fun `starts switched off, cooling at 21, and reports indoor and outdoor temperatures`() = runBlocking {
        val s = demo.getStat("demo")
        assertFalse(s.power)
        assertEquals(Mode.COOL, s.mode)
        assertEquals(21.0, s.presetTemp, 0.0)
        assertEquals(23.5, s.indoorTemp!!, 0.0)
        assertEquals(14.5, s.outdoorTemp!!, 0.0)
        assertNull(demo.scheme)
    }

    @Test
    fun `refuses writes until an account is registered, like a module`() = runBlocking {
        try {
            demo.setStat("demo", demo.getStat("demo").copy(power = true))
            fail("expected a refusal")
        } catch (e: WfRacException) {
            assertEquals("unit_refused", e.code)
        }
        assertFalse(demo.getStat("demo").power)
    }

    @Test
    fun `keeps what is written, as far as a frame carries it`() = runBlocking {
        demo.registerAccount("demo", "Europe/Amsterdam")
        val echoed = demo.setStat("demo", demo.getStat("demo").copy(power = true, mode = Mode.HEAT, presetTemp = 22.5, indoorTemp = 99.0))
        assertTrue(echoed.power)
        assertEquals(Mode.HEAT, echoed.mode)
        assertEquals(22.5, echoed.presetTemp, 0.0)
        assertEquals("the sensor is the unit's, not the writer's", 23.5, echoed.indoorTemp!!, 0.0)
        assertEquals(echoed, demo.getStat("demo"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a state that cannot be encoded is refused`(): Unit = runBlocking {
        demo.registerAccount("demo", "Europe/Amsterdam")
        demo.setStat("demo", demo.getStat("demo").copy(mode = null))
    }

    @Test
    fun `the room cools down towards the set temperature while running, and warms up again when off`() = runBlocking {
        demo.registerAccount("demo", "Europe/Amsterdam")
        demo.setStat("demo", demo.getStat("demo").copy(power = true, presetTemp = 20.0))

        clock += 5 * 60_000L
        assertEquals(22.5, demo.getStat("demo").indoorTemp!!, 0.0)
        clock += 60 * 60_000L
        assertEquals("never below the set temperature", 20.0, demo.getStat("demo").indoorTemp!!, 0.0)

        demo.setStat("demo", demo.getStat("demo").copy(power = false))
        clock += 20 * 60_000L
        assertEquals(21.0, demo.getStat("demo").indoorTemp!!, 0.0)
    }
}
