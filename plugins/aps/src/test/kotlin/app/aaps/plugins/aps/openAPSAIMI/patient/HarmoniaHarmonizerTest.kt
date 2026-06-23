package app.aaps.plugins.aps.openAPSAIMI.patient

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmoniaHarmonizerTest {

    @Test
    fun highFragility_softensDelivery() {
        val outcome = HarmoniaHarmonizer.evaluate(
            tree = null,
            simulation = null,
            bgMgdl = 122.0,
            deltaMgdl5m = 0.4,
            profileBasalUph = 1.0,
            proposedTbrUph = 2.0,
            eventualBgMgdl = 180.0,
            targetBgMgdl = 100.0,
            correctionFragilityScore = 0.72,
            postHyperExhaustionScore = 0.40,
        )
        assertThat(outcome?.posture).isEqualTo(HarmoniaHarmonizer.Posture.SOFTEN)
        assertThat(outcome?.tbrFactor).isLessThan(1.0)
    }
}
