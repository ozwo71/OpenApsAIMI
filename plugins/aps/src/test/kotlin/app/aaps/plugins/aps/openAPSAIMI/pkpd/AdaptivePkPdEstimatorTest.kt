package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdaptivePkPdEstimatorTest {

    @Test
    fun `test initial parameters`() {
        val estimator = AdaptivePkPdEstimator()
        val params = estimator.params()
        assertEquals(4.0, params.diaHrs, 0.0)
        assertEquals(75.0, params.peakMin, 0.0)
    }

    @Test
    fun `test update conditions not met`() {
        val estimator = AdaptivePkPdEstimator()
        val initialParams = estimator.params()

        // Window too small
        estimator.update(1000, 100.0, -5.0, 1.0, 0.0, 10, false)
        assertEquals(initialParams, estimator.params())

        // IOB too low
        estimator.update(1000, 100.0, -5.0, 0.1, 0.0, 60, false)
        assertEquals(initialParams, estimator.params())

        // Carbs active (gate is >15g; not a fast-fall — mild delta so COB is the skip reason)
        estimator.update(1000, 100.0, -2.0, 1.0, 20.0, 60, false)
        assertEquals(initialParams, estimator.params())

        // Exercise
        estimator.update(1000, 100.0, -5.0, 1.0, 0.0, 60, true)
        assertEquals(initialParams, estimator.params())

        // Hypo BG
        estimator.update(1000, 65.0, -2.0, 1.0, 0.0, 60, false)
        assertEquals(initialParams, estimator.params())

        // Near hypo while falling
        estimator.update(1000, 85.0, -2.0, 1.0, 0.0, 60, false)
        assertEquals(initialParams, estimator.params())

        // Fast fall (≤ FAST_FALL_DELTA_MGDL5)
        estimator.update(1000, 110.0, -4.0, 1.0, 0.0, 60, false)
        assertEquals(initialParams, estimator.params())
    }

    @Test
    fun `test update changes parameters`() {
        IsfTddProvider.set(45.0)
        val estimator = AdaptivePkPdEstimator()
        val initialParams = estimator.params()
        // Mild drop (above fast-fall skip) with mismatch vs model → structural peak/DIA should move.
        estimator.update(2000, 100.0, -2.5, 1.0, 0.0, 60, false)
        assertNotEquals(initialParams, estimator.params())
    }

    @Test
    fun `ultra-fast anchor allows peak to move below 75 min`() {
        IsfTddProvider.set(45.0)
        val bounds = PkPdBounds(
            diaMinH = 5.0,
            diaMaxH = 8.0,
            peakMinMin = 35.0,
            peakMinMax = 95.0,
            maxDiaChangePerDayH = 0.5,
            maxPeakChangePerDayMin = 5.0,
        )
        val cfg = PkPdLearningConfig(
            bounds = bounds,
            anchorDiaHrs = 4.0,
            anchorPeakMin = 55.0,
        )
        val estimator = AdaptivePkPdEstimator(
            initial = PkPdParams(diaHrs = 6.0, peakMin = 75.0),
            cfg = cfg,
        )
        // ~1 day of 5-min ticks: NORMAL 5 min/day rate can bind; soft-reg pulls toward ultra-fast 55.
        // Flat delta (not a fast-fall skip): model expects drop → err sign reinforces shorter peak.
        repeat(300) { tick ->
            estimator.update(
                epochMin = (tick + 1L) * 5L,
                bg = 100.0,
                deltaMgDlPer5 = 0.0,
                iobU = 2.0,
                carbsActiveG = 0.0,
                windowMin = 60,
                exerciseFlag = false,
            )
        }
        assertTrue(estimator.params().peakMin < 70.0)
    }

    @Test
    fun `dtDays uses loop tick fraction not full day on first update`() {
        IsfTddProvider.set(45.0)
        val bounds = PkPdBounds(maxDiaChangePerDayH = 0.24)
        val estimator = AdaptivePkPdEstimator(cfg = PkPdLearningConfig(bounds = bounds))
        val before = estimator.params().diaHrs
        estimator.update(5L, 100.0, -15.0, 2.0, 0.0, 60, false)
        val firstStep = kotlin.math.abs(estimator.params().diaHrs - before)
        assertTrue(firstStep < 0.05)
    }
}
