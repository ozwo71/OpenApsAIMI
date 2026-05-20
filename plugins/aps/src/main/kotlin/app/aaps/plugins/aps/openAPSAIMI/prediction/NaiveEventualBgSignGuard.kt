package app.aaps.plugins.aps.openAPSAIMI.prediction

import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage

/**
 * PR_NegativeIobEbgInflation: when AAPS [iobUnits] drifts negative while PKPD still reports peak insulin activity,
 * the legacy formula `bg - iob * sens` inverts sign and inflates naive eventual BG. This guard collapses that
 * term to current BG only in that structural-disagreement window (conservative; cannot inflate projections).
 *
 * Rounding matches [app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2.round] with digits = 0 (nearest long).
 */
internal data class NaiveEventualBgResolution(
    val naiveEventualBgMgdl: Double,
    val signGuardApplied: Boolean,
    val rawNaiveRoundedMgdl: Double,
)

internal object NaiveEventualBgSignGuard {

    const val MIN_PKPD_RELATIVE_ACTIVITY: Double = 0.70

    fun resolve(
        bgMgdl: Double,
        iobUnits: Double,
        sensMgDlPerU: Double,
        pkpdRelativeActivity: Double?,
        pkpdStage: InsulinActivityStage?,
    ): NaiveEventualBgResolution {
        val rawRounded = Math.round(bgMgdl - (iobUnits * sensMgDlPerU)).toDouble()
        val activity = pkpdRelativeActivity ?: 0.0
        val peakish =
            pkpdStage == InsulinActivityStage.PEAK ||
                pkpdStage == InsulinActivityStage.RISING
        val shouldCollapse =
            iobUnits < 0.0 &&
                activity >= MIN_PKPD_RELATIVE_ACTIVITY &&
                peakish
        val naive =
            if (shouldCollapse) {
                Math.round(bgMgdl).toDouble()
            } else {
                rawRounded
            }
        return NaiveEventualBgResolution(
            naiveEventualBgMgdl = naive,
            signGuardApplied = shouldCollapse,
            rawNaiveRoundedMgdl = rawRounded,
        )
    }
}
