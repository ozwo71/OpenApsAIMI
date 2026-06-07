package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStrategyHint
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecursiveBeliefAuthorityGateTest {

    @Test
    fun evaluate_blocks_authority_when_predictions_are_missing() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = false,
                phaseOutput = null,
                patternSnapshot = null,
                latentState = PhysioLatentState(sensorConfidence = 0.92, source = "test"),
                hypothesisState = null,
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = null,
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
        assertThat(decision.reasonCodes).contains("PRED_MISSING")
    }

    @Test
    fun evaluate_limits_false_meal_context_to_soft_authority() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY.copy(suppressMealInterpretation = true),
                latentState = PhysioLatentState(
                    mealProb = 0.24,
                    endogenousGlucoseDrive = 0.82,
                    transientResistanceProb = 0.58,
                    sensorConfidence = 0.84,
                    falseMealSuppression = true,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.22,
                    dawnEndogenousProb = 0.88,
                    stressProb = 0.08,
                    postHypoProb = 0.10,
                    dominant = UamHypothesisId.DAWN_ENDOGENOUS,
                    dominantConfidence = 0.88,
                    suppressMealInterpretation = true,
                ),
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = null,
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.softLimited).isTrue()
        assertThat(decision.reasonCodes).contains("MEAL_SUPPRESS")
        assertThat(decision.reasonCodes).contains("NON_MEAL_DOM")
    }

    @Test
    fun evaluate_allows_full_authority_when_quality_signals_are_clean() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(
                    mealProb = 0.86,
                    endogenousGlucoseDrive = 0.12,
                    circadianSiFactor = 0.95,
                    transientResistanceProb = 0.18,
                    sensorConfidence = 0.96,
                    postHypoReboundProb = 0.10,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.90,
                    dawnEndogenousProb = 0.06,
                    stressProb = 0.05,
                    postHypoProb = 0.04,
                    dominant = UamHypothesisId.MEAL,
                    dominantConfidence = 0.90,
                    suppressMealInterpretation = false,
                ),
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = SafetyRiskExportSnapshot(
                    phase = AimiRiskPhase.DECISION,
                    predictiveHypoSuppressed = false,
                    safetyGate = "clear",
                    haltRemainingPipeline = false,
                    mealContextActive = true,
                    mealRiseConfirmed = true,
                    compositeMinMgdl = 120.0,
                    predBgMgdl = 178.0,
                    eventualBgMgdl = 194.0,
                    uamTerminalMgdl = 220.0,
                    hypoThresholdMgdl = 75.0,
                ),
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.HARD)
        assertThat(decision.reasonCodes).containsExactly("READY")
        assertThat(decision.liftBlend).isEqualTo(1.0)
    }

    @Test
    fun evaluate_soft_limits_exercise_afterburn_mode_even_when_other_signals_are_clean() {
        val patientState = PatientStateSnapshot(
            timestampMs = 1_718_000_000_000L,
            mealProb = 0.22,
            endogenousGlucoseDrive = 0.12,
            transientResistanceProb = 0.18,
            sensorConfidence = 0.94,
        )
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(
                    mealProb = 0.22,
                    endogenousGlucoseDrive = 0.12,
                    transientResistanceProb = 0.18,
                    sensorConfidence = 0.94,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.22,
                    dominant = UamHypothesisId.NONE,
                    dominantConfidence = 0.0,
                    suppressMealInterpretation = false,
                ),
                patientState = patientState,
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.EXERCISE_AFTERBURN,
                    confidence = 0.82,
                    strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                    mealBias = 0.18,
                    protectionBias = 0.82,
                    userIntentConfidence = 0.86,
                    reasonCodes = listOf("CTX_ACTIVITY"),
                ),
                safetyRiskExport = null,
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.reasonCodes).contains("MODE_EXERCISE_AFTERBURN")
    }
}
