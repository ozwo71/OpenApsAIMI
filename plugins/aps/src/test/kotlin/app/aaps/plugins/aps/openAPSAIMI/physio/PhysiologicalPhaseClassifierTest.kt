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
    fun fast_morning_rise_prefers_stress_cortisol_over_meal() {
        val out = PhysiologicalPhaseClassifier.classify(
            dawnInput(delta = 4.0, bestT = 220.0, steps = 50).copy(
                heartRateBpm = 85,
                restingHeartRateBpm = 62,
            ),
        )
        assertEquals(PhysiologicalPhase.STRESS_CORTISOL, out.phase)
        assertEquals(HyperSeverityTier.EMERGING, out.policy.maxHtrTier)
    }

    @Test
    fun meal_like_rise_overrides_hormonal_at_lunch() {
        val out = PhysiologicalPhaseClassifier.classify(
            dawnInput(delta = 4.0, bestT = 220.0, steps = 50).copy(hourOfDay = 12),
        )
        assertEquals(PhysiologicalPhase.MEAL_UNDECLARED, out.phase)
    }

    /** Field replay 04:16 — BG ~109, inflated UAM bestT, dev still near target. */
    @Test
    fun dawn_near_target_uam_ramp_not_meal() {
        val out = PhysiologicalPhaseClassifier.classify(
            dawnInput(
                bg = 109.0,
                delta = 3.8,
                bestT = 242.0,
            ).copy(
                hourOfDay = 4,
                shortAvgDeltaMgdlPer5 = 3.2,
                combinedDeltaMgdlPer5 = 3.4,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, out.phase)
        assertTrue(out.policy.extendedDawnGuard)
    }

    @Test
    fun meal_dominant_blocks_stress_during_lunch_ramp() {
        val out = PhysiologicalPhaseClassifier.classify(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 167.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 4.2,
                shortAvgDeltaMgdlPer5 = 3.8,
                combinedDeltaMgdlPer5 = 4.0,
                mealCobG = 0.0,
                hourOfDay = 11,
                stepsLast15m = 200,
                heartRateBpm = 105,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 201.0,
                floorTerminalMgdl = 120.0,
                dwellAboveHighBgMinutes = 10,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
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

    /** Field replay 05/06 ~07:31 — UAM bestT inflated, COB=0; dawn hormonal near target, not meal. */
    @Test
    fun endogenous_cortisol_ramp_not_meal_undeclared() {
        val out = PhysiologicalPhaseClassifier.classify(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 119.0,
                targetBgMgdl = 115.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 3.0,
                shortAvgDeltaMgdlPer5 = 2.5,
                combinedDeltaMgdlPer5 = 2.8,
                mealCobG = 0.0,
                hourOfDay = 7,
                stepsLast15m = 188,
                heartRateBpm = 57,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 218.0,
                floorTerminalMgdl = 100.0,
                dwellAboveHighBgMinutes = 5,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, out.phase)
        assertEquals(HyperSeverityTier.OFF, out.policy.maxHtrTier)
        assertTrue(out.policy.suppressMealLikeScenario)
        val mealFlip = PhysiologicalPhaseClassifier.classify(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 127.0,
                targetBgMgdl = 115.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 3.0,
                shortAvgDeltaMgdlPer5 = 2.5,
                combinedDeltaMgdlPer5 = 2.8,
                mealCobG = 0.0,
                hourOfDay = 7,
                stepsLast15m = 188,
                heartRateBpm = 57,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 218.0,
                floorTerminalMgdl = 100.0,
                dwellAboveHighBgMinutes = 5,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertEquals(PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY, mealFlip.phase)
    }

    @Test
    fun endogenous_hysteresis_blocks_meal_flip() {
        EndogenousPhaseHysteresis.reset()
        val dawn = PhysiologicalPhaseClassifier.classifyWithHysteresis(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 119.0,
                targetBgMgdl = 115.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 3.0,
                shortAvgDeltaMgdlPer5 = 2.5,
                combinedDeltaMgdlPer5 = 2.8,
                mealCobG = 0.0,
                hourOfDay = 7,
                stepsLast15m = 50,
                heartRateBpm = 57,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 218.0,
                floorTerminalMgdl = 100.0,
                dwellAboveHighBgMinutes = 5,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, dawn.phase)
        val flipped = PhysiologicalPhaseClassifier.classify(
            dawnInput(bg = 126.0, delta = 1.0, bestT = 64.0).copy(
                floorTerminalMgdl = 60.0,
            ),
        )
        assertEquals(PhysiologicalPhase.OFF, flipped.phase)
        val held = PhysiologicalPhaseClassifier.classifyWithHysteresis(
            dawnInput(bg = 126.0, delta = 1.0, bestT = 64.0).copy(
                floorTerminalMgdl = 60.0,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, held.phase)
        EndogenousPhaseHysteresis.reset()
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
