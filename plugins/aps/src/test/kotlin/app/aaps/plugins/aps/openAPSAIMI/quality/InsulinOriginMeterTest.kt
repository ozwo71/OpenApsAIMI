package app.aaps.plugins.aps.openAPSAIMI.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Specification locks for [InsulinOriginMeter].
 *
 * The class is new, so neither test is a red-before / green-after proof of a bug. They write down
 * the two rules the meter must never break: the window stays bounded, and the parts always add back
 * to what was delivered. Both would have been green from the first line of the class.
 */
class InsulinOriginMeterTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun sample(
        index: Int,
        finalU: Double,
        modelOutputU: Double?,
        autodriveFloorU: Double?,
    ) = InsulinOriginMeter.Sample(
        timestampMs = t0 + index * 5 * minute,
        finalU = finalU,
        modelOutputU = modelOutputU,
        mpcOutputU = null,
        autodriveFloorU = autodriveFloorU,
        bindingStage = "FINAL",
        originOwner = "TEST",
    )

    /**
     * The window is a ring, not a log: 5000 ticks must leave 2016 kept, and the running sums must
     * still describe exactly those 2016. A leak here would make the shares drift slowly over days,
     * which is precisely the time scale the meter is meant to answer on.
     */
    @Test
    fun windowIsBoundedAndSharesSumToOne() {
        val meter = InsulinOriginMeter()
        for (i in 0 until 5000) {
            // Varied but always explained: sometimes the model leads, sometimes the floor does.
            val model = (i % 7) * 0.10
            val floor = (i % 5) * 0.15
            val delivered = maxOf(model, floor)
            meter.observe(sample(i, finalU = delivered, modelOutputU = model, autodriveFloorU = floor))
        }

        val reading = meter.read(t0 + 5000L * 5 * minute)
        assertEquals(2016, reading.tickCount)
        assertTrue(reading.deliveredU > 0.0)

        val parts = reading.modelOriginU + reading.floorOriginU + reading.otherOriginU
        val sum = parts / reading.deliveredU
        assertTrue(sum in 0.999..1.001, "parts must add back to the delivered amount, got $sum")

        assertNotNull(reading.modelOriginShare)
        assertNotNull(reading.floorOriginShare)
        assertEquals(1.0, reading.modelOriginShare!! + reading.floorOriginShare!!, 1e-3)
        assertEquals(t0 + (5000L - 2016L) * 5 * minute, reading.windowStartMs)
    }

    /**
     * The case the meter exists for: the model asked for nothing and the floor delivered anyway.
     * Every unit must be credited to the floor, none to the model, and the tick must be counted as
     * a model-zero tick. Crediting any of it to the model would hide exactly the failure being
     * looked for.
     */
    @Test
    fun modelZeroIsCountedWhenTheFloorDelivers() {
        val meter = InsulinOriginMeter()
        meter.observe(sample(0, finalU = 1.5, modelOutputU = 0.0, autodriveFloorU = 1.5))

        val reading = meter.read(t0)
        assertEquals(1, reading.tickCount)
        assertEquals(1.5, reading.deliveredU, 1e-9)
        assertEquals(0.0, reading.modelOriginU, 1e-9)
        assertEquals(1.5, reading.floorOriginU, 1e-9)
        assertEquals(0.0, reading.otherOriginU, 1e-9)
        assertEquals(1.5, reading.modelZeroDeliveredU, 1e-9)
        assertEquals(1, reading.modelZeroTickCount)
        assertEquals(0.0, reading.modelOriginShare!!, 1e-9)
        assertEquals(1.0, reading.floorOriginShare!!, 1e-9)
    }

    /** Nothing delivered yet: the shares must stay `null`, never `0.0`. */
    @Test
    fun sharesStayNullBeforeAnyDelivery() {
        val meter = InsulinOriginMeter()
        meter.observe(sample(0, finalU = 0.0, modelOutputU = 0.0, autodriveFloorU = 0.0))

        val reading = meter.read(t0)
        assertEquals(1, reading.tickCount)
        assertEquals(0.0, reading.deliveredU, 1e-9)
        assertNull(reading.modelOriginShare)
        assertNull(reading.floorOriginShare)
    }
}
