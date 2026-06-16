package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhysiologicalPatternPolicyTest {

    @Test
    fun meal_active_uses_meal_cap_not_activity_min_cap() {
        val snapshot = PhysiologicalPatternPolicy.aggregate(
            listOf(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
                    confidence = 0.88,
                    reason = "mealLike",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.EXERCISE_ACUTE,
                    confidence = 0.85,
                    reason = "steps+hr",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.MEAL_FIRST_WAVE,
                    confidence = 0.80,
                    reason = "FIRST_WAVE",
                ),
            ),
        )

        assertThat(snapshot.smbCapU).isEqualTo(1.20)
        assertThat(snapshot.suppressHyperRelease).isFalse()
        assertThat(snapshot.suppressMealInterpretation).isFalse()
    }

    @Test
    fun non_meal_context_keeps_restrictive_min_cap() {
        val snapshot = PhysiologicalPatternPolicy.aggregate(
            listOf(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.EXERCISE_ACUTE,
                    confidence = 0.85,
                    reason = "steps+hr",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
                    confidence = 0.88,
                    reason = "surveillance",
                ),
            ),
        )

        assertThat(snapshot.smbCapU).isEqualTo(0.40)
        assertThat(snapshot.suppressHyperRelease).isTrue()
    }
}
