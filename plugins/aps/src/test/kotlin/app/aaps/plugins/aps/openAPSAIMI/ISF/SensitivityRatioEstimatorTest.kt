package app.aaps.plugins.aps.openAPSAIMI.ISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The ratio must move only on evidence, move slowly, and stay inside the domain where the profile
 * still means something. See `docs/adr/0008-isf-decision-architecture.md`.
 */
class SensitivityRatioEstimatorTest {

    private val profileIsf = 30.0
    private val profileBasal = 0.6

    private fun feed(
        estimator: SensitivityRatioEstimator,
        minutes: Int,
        bgStart: Double,
        bgEnd: Double,
        iobStart: Double,
        iobEnd: Double,
        deliveredBasal: Double = profileBasal,
        smbAt: Set<Int> = emptySet(),
        startMs: Long = 0L,
    ): Long {
        val steps = minutes / 5
        var last = startMs
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            last = startMs + i * 5L * 60_000L
            estimator.observe(
                SensitivityRatioEstimator.Sample(
                    timestampMs = last,
                    bgMgdl = bgStart + (bgEnd - bgStart) * f,
                    iobU = iobStart + (iobEnd - iobStart) * f,
                    profileBasalUph = profileBasal,
                    deliveredBasalUph = deliveredBasal,
                    smbU = if (i in smbAt) 0.5 else 0.0,
                    profileIsfMgdl = profileIsf,
                ),
            )
        }
        return last
    }

    @Test
    fun `starts neutral and trusts the profile until something is measured`() {
        val estimator = SensitivityRatioEstimator()

        assertThat(estimator.ratio).isWithin(1e-9).of(1.0)
        assertThat(estimator.observationCount).isEqualTo(0)
        assertThat(estimator.sensitivityMgdl(profileIsf)).isWithin(1e-9).of(profileIsf)
    }

    @Test
    fun `a clean falling window with no bolus produces an observation`() {
        val estimator = SensitivityRatioEstimator()

        // 30 min of quiet run-up, then a 50 min window: BG 140 -> 110 while IOB drains 2.0 -> 1.0.
        val afterRunUp = feed(estimator, minutes = 30, bgStart = 140.0, bgEnd = 140.0, iobStart = 2.2, iobEnd = 2.0)
        feed(estimator, minutes = 50, bgStart = 140.0, bgEnd = 110.0, iobStart = 2.0, iobEnd = 1.0, startMs = afterRunUp + 300_000L)

        assertThat(estimator.observationCount).isAtLeast(1)
        val obs = requireNonNull(estimator.lastObservation)
        assertThat(obs.bgDropMgdl).isGreaterThan(0.0)
        assertThat(obs.insulinActedU).isGreaterThan(SensitivityRatioEstimator.MIN_ACTED_U)
    }

    @Test
    fun `a bolus inside the window disqualifies it`() {
        val estimator = SensitivityRatioEstimator()

        // Flat run-up: no fall, so nothing observable comes out of it on its own.
        val afterRunUp = feed(estimator, minutes = 60, bgStart = 140.0, bgEnd = 140.0, iobStart = 2.4, iobEnd = 2.2)
        // The bolus lands early enough that every window reaching the fall also contains it.
        feed(
            estimator, minutes = 50, bgStart = 140.0, bgEnd = 110.0, iobStart = 2.2, iobEnd = 1.2,
            smbAt = setOf(1), startMs = afterRunUp + 300_000L,
        )

        assertThat(estimator.observationCount).isEqualTo(0)
        assertThat(estimator.ratio).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `a rising window is rejected because carbs may be arriving`() {
        val estimator = SensitivityRatioEstimator()

        val afterRunUp = feed(estimator, minutes = 30, bgStart = 110.0, bgEnd = 110.0, iobStart = 2.2, iobEnd = 2.0)
        feed(estimator, minutes = 50, bgStart = 110.0, bgEnd = 150.0, iobStart = 2.0, iobEnd = 1.0, startMs = afterRunUp + 300_000L)

        assertThat(estimator.observationCount).isEqualTo(0)
    }

    @Test
    fun `the basal deficit is not credited with the fall`() {
        val full = SensitivityRatioEstimator()
        val zeroBasal = SensitivityRatioEstimator()

        for (estimator in listOf(full, zeroBasal)) {
            val delivered = if (estimator === zeroBasal) 0.0 else profileBasal
            val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0, deliveredBasal = delivered)
            feed(estimator, 50, 140.0, 110.0, 2.0, 1.0, deliveredBasal = delivered, startMs = afterRunUp + 300_000L)
        }

        // Same fall, same IOB decay: with the loop at zero basal, less insulin actually acted, so
        // each unit did more, so the measured sensitivity is higher.
        val withDeficit = requireNonNull(zeroBasal.lastObservation).ratio
        val withoutDeficit = requireNonNull(full.lastObservation).ratio
        assertThat(withDeficit).isGreaterThan(withoutDeficit)
    }

    @Test
    fun `one observation moves the state only slightly`() {
        val estimator = SensitivityRatioEstimator()

        val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0)
        feed(estimator, 50, 140.0, 110.0, 2.0, 1.0, startMs = afterRunUp + 300_000L)

        // Sensitivity is a slow state: a single window must not swing it.
        assertThat(estimator.ratio).isAtLeast(0.9)
        assertThat(estimator.ratio).isAtMost(1.1)
    }

    @Test
    fun `the commanded sensitivity stays inside the band around the profile`() {
        val estimator = SensitivityRatioEstimator()

        assertThat(estimator.sensitivityMgdl(profileIsf))
            .isAtLeast(profileIsf * SensitivityRatioEstimator.MIN_RATIO)
        assertThat(estimator.sensitivityMgdl(profileIsf))
            .isAtMost(profileIsf * SensitivityRatioEstimator.MAX_RATIO)
    }

    @Test
    fun `the BG modulation is neutral at the reference and lowers sensitivity when high`() {
        val atReference = SensitivityRatioEstimator.bgModulation(
            SensitivityRatioEstimator.MODULATION_REFERENCE_MGDL,
        )
        assertThat(atReference).isWithin(1e-9).of(1.0)
        assertThat(SensitivityRatioEstimator.bgModulation(200.0)).isLessThan(1.0)
        assertThat(SensitivityRatioEstimator.bgModulation(70.0)).isGreaterThan(1.0)
    }

    private fun <T : Any> requireNonNull(value: T?): T = requireNotNull(value) { "expected an observation" }
}
