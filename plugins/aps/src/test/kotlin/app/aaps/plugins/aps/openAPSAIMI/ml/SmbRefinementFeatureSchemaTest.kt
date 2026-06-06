package app.aaps.plugins.aps.openAPSAIMI.ml

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
        )

        assertThat(features.size).isEqualTo(SmbRefinementFeatureSchema.INPUT_SIZE)
        assertThat(features.copyOfRange(10, 14).toList())
            .containsExactly(0.41f, 0.28f, 0.91f, 0.64f)
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
        assertThat(parsed.copyOfRange(10, 14).toList())
            .containsExactly(0f, 0f, 1f, 0f)
            .inOrder()
    }
}
