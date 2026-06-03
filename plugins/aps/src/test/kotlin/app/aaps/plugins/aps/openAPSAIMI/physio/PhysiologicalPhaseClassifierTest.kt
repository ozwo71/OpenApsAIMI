package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhysiologicalPhaseClassifierTest {

    private fun dawnInput(
        bg: Double = 122.0,
        delta: Double = 2.0,
        bestT: Double = 145.0,
        steps: Int = 800,
    ) = PhysiologicalPhaseClassifier.Input(
        bgMgdl = bg,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 140.0,
        deltaMgdlPer5 = delta,
        shortAvgDeltaMgdlPer5 = 1.8,
        combinedDeltaMgdlPer5 = 1.9,
        mealCobG = 0.0,
        hourOfDay = 7,
        stepsLast15m = steps,
        heartRateBpm = 78,
        restingHeartRateBpm = 62,
        bestTerminalMgdl = bestT,
        floorTerminalMgdl = 95.0,
        dwellAboveHighBgMinutes = 5,
        wCycleEnabled = false,
        wCycleTrackingMode = null,
        wCyclePhase = null,
    )

    @Test
    fun morning_near_target_male_circadian() {
        val out = PhysiologicalPhaseClassifier.classify(dawnInput())
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, out.phase)
        assertTrue(out.policy.extendedDawnGuard)
        assertEquals(HyperSeverityTier.OFF, out.policy.maxHtrTier)
    }

    @Test
    fun meal_like_rise_overrides_hormonal() {
        val out = PhysiologicalPhaseClassifier.classify(
            dawnInput(delta = 4.0, bestT = 220.0, steps = 50),
        )
        assertEquals(PhysiologicalPhase.MEAL_UNDECLARED, out.phase)
    }

    @Test
    fun htr_off_under_hormonal_policy() {
        val phase = PhysiologicalPhaseClassifier.classify(dawnInput())
        val htr = app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseEvaluator.evaluate(
            app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = 122.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 2.0,
                shortAvgDeltaMgdlPer5 = 1.8,
                combinedDeltaMgdlPer5 = 1.9,
                floorTerminalMgdl = 95.0,
                bestTerminalMgdl = 145.0,
                tdd24hU = 55.0,
                iobU = 2.0,
                maxIobU = 20.0,
                maxSmbEffectiveU = 5.0,
                v3SmbU = 1.9,
                dwellAboveHighBgMinutes = 5,
                trajectoryType = null,
                minPredictedBgMgdl = 110.0,
                aggressive = true,
                behavioralRisk = phase.policy,
            ),
        )
        assertTrue(!htr.active)
        assertTrue(htr.reason.contains("physio"))
    }

    @Test
    fun scenario_cap_limits_best_terminal() {
        val policy = BehavioralRiskPolicy.forPhase(
            PhysiologicalPhase.DAWN_CORTISOL,
            0.9,
            "test",
        )
        val capped = HormonalScenarioTerminalCap.capBestTerminalMgdl(120.0, 280.0, policy)
        assertEquals(170.0, capped, 0.01)
    }
}
