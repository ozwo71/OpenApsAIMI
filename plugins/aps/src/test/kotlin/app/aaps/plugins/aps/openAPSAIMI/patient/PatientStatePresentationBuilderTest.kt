package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PatientStatePresentationBuilderTest {

    @Test
    fun build_formats_patient_mode_story_for_clinical_ui() {
        val presentation = PatientStatePresentationBuilder.build(
            snapshot = PatientRuntimeSnapshot(
                patientState = PatientStateSnapshot(
                    timestampMs = 1_718_000_000_000L,
                    phase = PhysiologicalPhase.DAWN_CORTISOL,
                    phaseConfidence = 0.84,
                    mealAbsorptionPhase = MealAbsorptionPhase.NONE,
                    mealProb = 0.22,
                    endogenousGlucoseDrive = 0.88,
                    transientResistanceProb = 0.52,
                    sensorConfidence = 0.86,
                    falseMealSuppression = true,
                    uamDominant = UamHypothesisId.DAWN_ENDOGENOUS,
                    uamDominantConfidence = 0.88,
                    userIntent = UserIntentSummary(
                        enabled = true,
                        intentCount = 1,
                        avgConfidence = 0.82,
                        hasStress = true,
                        dominantIntent = "STRESS",
                    ),
                ),
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.DAWN_ENDOGENOUS,
                    confidence = 0.88,
                    strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                    mealBias = 0.16,
                    protectionBias = 0.78,
                    userIntentConfidence = 0.82,
                    reasonCodes = listOf("LATENT_ENDOGENOUS", "FALSE_MEAL_SUPPRESS"),
                ),
                updatedAtMs = 1_718_000_000_000L,
            ),
            nowMs = 1_718_000_180_000L,
        )

        assertThat(presentation.updatedSummary).isEqualTo("Updated 3 minutes ago")
        assertThat(presentation.modeHeadline).isEqualTo("Dawn Endogenous (88%)")
        assertThat(presentation.narrative).contains("endogenous drive")
        assertThat(presentation.physiologySummary).contains("Dawn Cortisol")
        assertThat(presentation.intentSummary).contains("Dominant Stress")
        assertThat(presentation.signalSummary).contains("Endogenous 88%")
        assertThat(presentation.deliverySummary).contains("Basal Bridge")
        assertThat(presentation.reasonSummary).contains("False meal suppression")
    }
}
