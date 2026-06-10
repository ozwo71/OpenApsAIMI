package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStrategyHint
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SmbRefinementFeatureSchemaTest {

    @Test
    fun build_runtime_features_appends_latent_physio_axes_and_trend_indicator() {
        val features = SmbRefinementFeatureSchema.buildRuntimeFeatures(
            baseFeatures = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f),
            trendIndicator = 0.73f,
            physioLatentState = PhysioLatentState(
                mealProb = 0.41,
                endogenousGlucoseDrive = 0.28,
                circadianSiFactor = 0.91,
                transientResistanceProb = 0.64,
                postHypoReboundProb = 0.22,
                sleepDebtScore = 0.15,
                sensorConfidence = 0.94,
                falseMealSuppression = false,
                source = "test",
            ),
            patientModeDecision = PatientModeOrchestrator.Decision(
                mode = PatientMode.FAST_MEAL,
                confidence = 0.89,
                strategyHint = PatientStrategyHint.SMB_PRIORITY,
                mealBias = 0.90,
                protectionBias = 0.18,
                userIntentConfidence = 0.84,
                reasonCodes = listOf("MEAL_FIRST_WAVE"),
            ),
            causalStatePosterior = CausalStatePosterior(
                fastMealProb = 0.83,
                prolongedMealProb = 0.18,
                dawnEndogenousProb = 0.12,
                postHypoRecoveryProb = 0.04,
                stressResistanceProb = 0.10,
                exerciseAfterburnProb = 0.06,
                inflammatoryDriftProb = 0.08,
                absorptionUncertainProb = 0.11,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.83,
                learningQuality = 0.79,
            ),
        )

        assertThat(features.size).isEqualTo(SmbRefinementFeatureSchema.INPUT_SIZE)
        assertThat(features.copyOfRange(10, 14).toList())
            .containsExactly(0.41f, 0.28f, 0.91f, 0.64f)
            .inOrder()
        assertThat(features.copyOfRange(14, 17).toList())
            .containsExactly(0.90f, 0.18f, 0.84f)
            .inOrder()
        assertThat(features.copyOfRange(17, 20).toList())
            .containsExactly(0.83f, 0.12f, 0.79f)
            .inOrder()
        assertThat(features.last()).isEqualTo(0.73f)
    }

    @Test
    fun parse_training_features_keeps_backward_compatibility_for_old_csv_rows() {
        val headers = listOf(
            "dateStr",
            "bg",
            "iob",
            "cob",
            "delta",
            "shortAvgDelta",
            "longAvgDelta",
            "tdd7DaysPerHour",
            "tdd2DaysPerHour",
            "tddPerHour",
            "tdd24HrsPerHour",
            "predictedSMB",
            "smbGiven",
        )
        val cols = listOf(
            "06/06/2026 08:00",
            "152",
            "1.2",
            "9.0",
            "3.5",
            "2.9",
            "1.7",
            "0.8",
            "0.7",
            "0.9",
            "1.0",
            "0.4",
            "0.35",
        )

        val parsed = SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols)

        assertThat(parsed).isNotNull()
        parsed!!
        assertThat(parsed.size).isEqualTo(SmbRefinementFeatureSchema.INPUT_SIZE - 1)
        assertThat(parsed.copyOfRange(10, 20).toList())
            .containsExactly(0f, 0f, 1f, 0f, 0.45f, 0.22f, 0f, 0f, 0f, 1f)
            .inOrder()
    }
}
