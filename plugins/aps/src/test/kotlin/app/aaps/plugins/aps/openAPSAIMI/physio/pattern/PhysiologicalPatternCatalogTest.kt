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
    fun poor_sleep_morning_rise_suppresses_hyper_and_meal() {
        val def = PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE)
        assertThat(def.suppressMealInterpretation).isTrue()
        assertThat(def.suppressHyperRelease).isTrue()
        assertThat(def.suppressWaveletBoost).isTrue()
        assertThat(def.smbCapU).isEqualTo(0.50)
    }
}
