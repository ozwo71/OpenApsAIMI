package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkpdLearningWindowGateTest {

    /** Records one delta every 5 min from [fromMin] up to (not including) [toMin]. */
    private fun PkpdLearningWindowGate.fill(fromMin: Long, toMin: Long, delta: (Long) -> Double) {
        var t = fromMin
        while (t < toMin) {
            record(t, delta(t))
            t += 5
        }
    }

    @Test
    fun quietHourPasses() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { -1.0 }
        val verdict = gate.evaluate(65, -0.5, mealConfidence = 0.2)
        assertTrue(verdict.pass)
        assertNull(verdict.blockedBy)
        assertEquals(-1.0, verdict.maxRecentDeltaMgdl!!, 1e-9)
    }

    @Test
    fun riseInsideTheWindowBlocks() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { t -> if (t == 30L) 4.0 else 0.0 }
        val verdict = gate.evaluate(65, 0.0, mealConfidence = 0.2)
        assertFalse(verdict.pass)
        assertEquals(PkpdLearningWindowGate.BLOCK_RECENT_RISE, verdict.blockedBy)
    }

    @Test
    fun riseOlderThanTheWindowIsForgotten() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 135) { t -> if (t < 60L) 6.0 else -1.0 }
        assertTrue(gate.evaluate(135, -1.0, mealConfidence = 0.0).pass)
    }

    @Test
    fun risingNowBlocks() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        assertEquals(PkpdLearningWindowGate.BLOCK_RISING_NOW, gate.evaluate(65, 1.5, 0.0).blockedBy)
    }

    /** The meal belief has a background plateau near 0.40: that must NOT block, 0.50 must. */
    @Test
    fun mealVetoSitsAboveTheBackgroundPlateau() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        assertTrue(gate.evaluate(65, 0.0, mealConfidence = 0.40).pass)
        assertEquals(PkpdLearningWindowGate.BLOCK_MEAL_BELIEF, gate.evaluate(65, 0.0, mealConfidence = 0.50).blockedBy)
    }

    /** A loop gap is not a quiet hour. */
    @Test
    fun shortOrGappedHistoryBlocks() {
        val empty = PkpdLearningWindowGate()
        assertEquals(PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, empty.evaluate(65, 0.0, 0.0).blockedBy)

        val gapped = PkpdLearningWindowGate()
        gapped.fill(35, 65) { 0.0 }
        val verdict = gapped.evaluate(65, 0.0, 0.0)
        assertEquals(PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, verdict.blockedBy)
        assertEquals(30L, verdict.coveredMin)
    }

    /** The tick being judged is not part of its own window, and a repeated minute replaces the old one. */
    @Test
    fun sameMinuteReplacesAndCurrentTickIsExcluded() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        gate.record(65, 9.0)
        gate.record(65, 0.0)
        assertTrue(gate.evaluate(70, 0.0, 0.0).pass)
        // A rise recorded at the judged minute itself is not in its window.
        gate.record(75, 9.0)
        assertTrue(gate.evaluate(75, 0.0, 0.0).pass)
    }

    @Test
    fun nonFiniteDeltaBlocksAndIsNotRecorded() {
        val gate = PkpdLearningWindowGate()
        gate.fill(0, 65) { 0.0 }
        gate.record(65, Double.NaN)
        assertEquals(PkpdLearningWindowGate.BLOCK_NO_DELTA, gate.evaluate(70, Double.NaN, 0.0).blockedBy)
        assertTrue(gate.evaluate(70, 0.0, 0.0).pass)
    }

    @Test
    fun clockGoingBackRestartsHistory() {
        val gate = PkpdLearningWindowGate()
        gate.fill(100, 165) { 0.0 }
        gate.record(10, 0.0)
        assertEquals(PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, gate.evaluate(15, 0.0, 0.0).blockedBy)
    }
}
