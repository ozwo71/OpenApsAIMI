package app.aaps.plugins.aps.openAPSAIMI.release

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HyperTrajectoryHypoCredibilityTest {

    @Test
    fun minPred39_at_bg243_not_credible() {
        assertFalse(
            HyperTrajectoryHypoCredibility.isMinPredictedCredible(
                bgMgdl = 243.0,
                minPredictedBgMgdl = 39.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
            ),
        )
    }

    @Test
    fun sanitize_uplifts_eventual_when_best_high() {
        val (predicted, eventual) = HyperTrajectoryHypoCredibility.sanitizeTerminalsForHypoGuard(
            bgMgdl = 243.0,
            predictedBgMgdl = 39.0,
            eventualBgMgdl = 39.0,
            minPredictedBgMgdl = 39.0,
            targetBgMgdl = 100.0,
            highBgPreferenceMgdl = 140.0,
            scenarioBestTerminalMgdl = 320.0,
        )
        assertTrue(eventual > 150.0)
        assertTrue(predicted > 150.0)
    }
}
