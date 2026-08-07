package app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Ra must return towards zero when nothing supports it any more.
 *
 * The estimator had no prediction step: `innovation` subtracted `lastRa` itself, so as soon as Ra
 * explained the observed velocity the innovation went to zero and Ra stayed put. Production trace of
 * 2026-08-07 showed it pinned at 1.89 for two hours, then 1.24, then 1.18 for three and a half
 * hours. The three gates that read it (0.6 / 0.7 / 0.8) were therefore open on 82 % of ticks.
 */
class ContinuousStateEstimatorRaDecayTest {

    private val logger = mockk<AAPSLogger>(relaxed = true)

    private fun quietState(ra: Double) = AutoDriveState.createSafe(
        bg = 110.0,
        bgVelocity = 0.0,
        iob = 0.0,
        estimatedRa = ra,
        physiologicalStressMask = DoubleArray(4),
    )

    private fun risingState(ra: Double) = AutoDriveState.createSafe(
        bg = 150.0,
        bgVelocity = 3.0,
        iob = 1.0,
        estimatedRa = ra,
        combinedDelta = 3.0,
        physiologicalStressMask = DoubleArray(4),
    )

    @Test
    fun `Ra decays towards zero over a quiet stretch instead of holding a plateau`() {
        val estimator = ContinuousStateEstimator(logger)

        // Feed a rise so the estimator picks a meal up.
        var state = risingState(0.0)
        repeat(6) { state = estimator.updateAndPredict(risingState(state.estimatedRa)) }
        val raAfterRise = state.estimatedRa
        assertThat(raAfterRise).isGreaterThan(0.5)

        // Then a long quiet stretch: BG flat, no insulin, nothing to explain.
        repeat(40) { state = estimator.updateAndPredict(quietState(state.estimatedRa)) }

        assertThat(state.estimatedRa).isLessThan(raAfterRise)
        assertThat(state.estimatedRa).isLessThan(0.6) // below the lowest gate that reads it
    }

    @Test
    fun `a sustained rise still holds Ra up`() {
        val estimator = ContinuousStateEstimator(logger)

        var state = risingState(0.0)
        repeat(12) { state = estimator.updateAndPredict(risingState(state.estimatedRa)) }

        // The decay must not fight a meal that is genuinely still going.
        assertThat(state.estimatedRa).isGreaterThan(0.8)
    }

    @Test
    fun `decay constants stay physiological`() {
        // Half-life around 30 min, and a single long gap must not wipe the state in one step.
        assertThat(ContinuousStateEstimator.RA_DECAY_TAU_MIN).isAtLeast(20.0)
        assertThat(ContinuousStateEstimator.RA_DECAY_TAU_MIN).isAtMost(90.0)
        assertThat(ContinuousStateEstimator.RA_DECAY_MAX_DT_MIN).isAtMost(60.0)
    }
}
