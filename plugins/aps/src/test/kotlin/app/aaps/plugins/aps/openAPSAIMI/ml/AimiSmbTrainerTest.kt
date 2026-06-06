package app.aaps.plugins.aps.openAPSAIMI.ml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiSmbTrainerTest {

    @Test
    fun refine_returns_predicted_smb_when_feature_vector_size_is_unexpected() {
        val predictedSmb = 0.65f

        val refined = AimiSmbTrainer.refine(
            predictedSmb = predictedSmb,
            features = FloatArray(SmbRefinementFeatureSchema.INPUT_SIZE - 1) { 0f },
        )

        assertThat(refined).isEqualTo(predictedSmb)
    }
}
