package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DecisionPredictionAuthorityResolverTest {

    @Test
    fun mealRiseUpliftsDecisionEventualAbovePkpd() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 148.0,
            pkpdEventualMgdl = 112.0,
            scenarioProjection = scenario(floorTerminal = 104.0, bestTerminal = 184.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.FIRST_WAVE, priority = true),
            hypothesisState = UamHypothesisState(
                mealProb = 0.78,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.78,
            ),
            latentState = PhysioLatentState(),
            trajectoryAnalysis = trajectory(TrajectoryType.OPEN_DIVERGING),
            physioPolicy = null,
            uamConfidence = 0.62,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_MEAL_UPLIFT, authority.source)
        assertTrue(authority.scenarioUpliftApplied)
        assertEquals(184.0, authority.eventualTerminalMgdl, 0.001)
        assertEquals(104.0, authority.predTerminalMgdl, 0.001)
    }

    @Test
    fun falseMealSuppressionKeepsPkpdAuthorityDuringCortisolLikeRise() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 142.0,
            pkpdEventualMgdl = 118.0,
            scenarioProjection = scenario(floorTerminal = 116.0, bestTerminal = 156.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.NONE, priority = false),
            hypothesisState = UamHypothesisState(
                dawnEndogenousProb = 0.82,
                dominant = UamHypothesisId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.82,
                suppressMealInterpretation = true,
            ),
            latentState = PhysioLatentState(falseMealSuppression = true),
            trajectoryAnalysis = trajectory(TrajectoryType.SLOW_DRIFT),
            physioPolicy = null,
            uamConfidence = 0.18,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL, authority.source)
        assertTrue(authority.falseMealSuppression)
        assertFalse(authority.scenarioUpliftApplied)
        assertEquals(118.0, authority.eventualTerminalMgdl, 0.001)
        assertEquals(116.0, authority.predTerminalMgdl, 0.001)
    }

    @Test
    fun smallScenarioGapUsesConsensusWithoutForcingMealInterpretation() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 132.0,
            pkpdEventualMgdl = 109.0,
            scenarioProjection = scenario(floorTerminal = 108.0, bestTerminal = 111.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.NONE, priority = false),
            hypothesisState = UamHypothesisState(
                postHypoProb = 0.76,
                dominant = UamHypothesisId.POST_HYPO,
                dominantConfidence = 0.76,
            ),
            latentState = PhysioLatentState(),
            trajectoryAnalysis = trajectory(TrajectoryType.CLOSING_CONVERGING),
            physioPolicy = null,
            uamConfidence = 0.10,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_CONSENSUS, authority.source)
        assertFalse(authority.scenarioUpliftApplied)
        assertEquals(111.0, authority.eventualTerminalMgdl, 0.001)
        assertEquals(108.0, authority.predTerminalMgdl, 0.001)
    }

    private fun scenario(
        floorTerminal: Double,
        bestTerminal: Double,
    ): ScenarioProjectionPair =
        ScenarioProjectionPair(
            clinicalFloor = ScenarioProjectionCurve.fromRawPoints(
                ScenarioProjectionKind.CLINICAL_FLOOR,
                listOf(140.0, floorTerminal),
            ),
            scenarioBest = ScenarioProjectionCurve.fromRawPoints(
                ScenarioProjectionKind.SCENARIO_BEST,
                listOf(140.0, bestTerminal),
            ),
            cobPointsMgdl = emptyList(),
            ztPointsMgdl = emptyList(),
            contributors = emptyList(),
            trajectoryType = null,
        )

    private fun mealOutput(
        phase: MealAbsorptionPhase,
        priority: Boolean,
    ): MealAbsorptionPhaseEngine.Output =
        MealAbsorptionPhaseEngine.Output(
            phase = phase,
            belief = if (phase.isActive) 0.70 else 0.20,
            reason = phase.name,
            deltaMgdlPer5 = 0.0,
            gapMgdl = 0.0,
            bestTerminalMgdl = 0.0,
            memoryActive = false,
            waveCount = 0,
            mealDeliveryPriority = priority,
            chronoPrior = 0.0,
            kineticScore = 0.0,
            trajectoryScore = 0.0,
            physioScore = 0.0,
        )

    private fun trajectory(type: TrajectoryType): TrajectoryAnalysis =
        TrajectoryAnalysis(
            classification = type,
            metrics = TrajectoryMetrics(
                curvature = 0.0,
                convergenceVelocity = 0.0,
                coherence = 1.0,
                energyBalance = 0.0,
                openness = 0.0,
                isConverging = type == TrajectoryType.CLOSING_CONVERGING,
                isDiverging = type == TrajectoryType.OPEN_DIVERGING || type == TrajectoryType.SLOW_DRIFT,
                isTightSpiral = false,
                hasLowCoherence = false,
                isStable = false,
            ),
            modulation = TrajectoryModulation.NEUTRAL,
            warnings = emptyList(),
            stableOrbitDistance = 0.0,
            predictedConvergenceTime = null,
        )
}
