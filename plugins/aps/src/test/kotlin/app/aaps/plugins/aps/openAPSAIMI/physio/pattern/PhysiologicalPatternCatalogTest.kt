package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhysiologicalPatternCatalogTest {

    @Test
    fun every_pattern_id_has_catalog_definition() {
        for (id in PhysiologicalPatternId.entries) {
            val def = PhysiologicalPatternCatalog.definitionOf(id)
            assertThat(def.id).isEqualTo(id)
            assertThat(def.category).isEqualTo(id.category)
            assertThat(def.dominantScaleMinutes).isAtLeast(15)
        }
        assertThat(PhysiologicalPatternId.entries.size).isEqualTo(28)
    }

    @Test
    fun all_meal_patterns_define_an_smb_cap() {
        // Cap coverage must be intrinsic to meal classification: when a meal pattern displaces a
        // capped pattern mid-rise, patternCapU must not silently become null (HTR min-cap skip).
        val mealPatterns = listOf(
            PhysiologicalPatternId.MEAL_DECLARED,
            PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
            PhysiologicalPatternId.MEAL_FIRST_WAVE,
            PhysiologicalPatternId.MEAL_SECOND_WAVE,
        )
        for (id in mealPatterns) {
            val def = PhysiologicalPatternCatalog.definitionOf(id)
            assertThat(def.smbCapU).isNotNull()
            // Generous (meal response must not be strangled) but finite.
            assertThat(def.smbCapU!!).isAtLeast(1.0)
            assertThat(def.smbCapU!!).isAtMost(2.0)
        }
        // Declared meals are the most trusted context — their cap must be the loosest of the four.
        val declared = PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.MEAL_DECLARED).smbCapU!!
        for (id in mealPatterns - PhysiologicalPatternId.MEAL_DECLARED) {
            assertThat(PhysiologicalPatternCatalog.definitionOf(id).smbCapU!!).isAtMost(declared)
        }
    }

    @Test
    fun poor_sleep_morning_rise_suppresses_hyper_and_meal() {
        val def = PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE)
        assertThat(def.suppressMealInterpretation).isTrue()
        assertThat(def.suppressHyperRelease).isTrue()
        assertThat(def.suppressWaveletBoost).isTrue()
        assertThat(def.smbCapU).isEqualTo(0.50)
    }
}
