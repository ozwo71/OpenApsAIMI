package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HypoLgsBlockReasonTest {

    @Test
    fun detect_ignores_min_curve_when_htr_or_rbt_already_invalidated_it() {
        val reason = HypoLgsBlockReason.detect(
            bgNow = 214.0,
            predicted = 156.0,
            eventual = 164.0,
            minPredictedCurve = 62.0,
            hypo = 75.0,
            delta = 1.8,
            ignoreMinPredictedCurve = true,
        )

        assertThat(reason).isNull()
    }

    @Test
    fun detect_keeps_min_curve_block_when_it_is_not_explicitly_invalidated() {
        val reason = HypoLgsBlockReason.detect(
            bgNow = 214.0,
            predicted = 156.0,
            eventual = 164.0,
            minPredictedCurve = 62.0,
            hypo = 75.0,
            delta = 1.8,
        )

        assertThat(reason).isEqualTo(HypoLgsBlockReason.PREDICTED_MIN_CURVE)
    }
}
