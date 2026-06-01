package app.aaps.plugins.aps.openAPSAIMI.release

import kotlin.math.max

/**
 * Filters incoherent hypo signals when BG is in a hyper band (Phase 0 / HTR).
 */
object HyperTrajectoryHypoCredibility {

    fun highBgBandMgdl(targetBgMgdl: Double, highBgPreferenceMgdl: Double): Double =
        (highBgPreferenceMgdl - targetBgMgdl).coerceAtLeast(30.0)

    fun hypoCredibilityDropMgdl(devAboveTargetMgdl: Double): Double =
        35.0 + 0.1 * devAboveTargetMgdl.coerceAtLeast(0.0)

    fun isMinPredictedCredible(
        bgMgdl: Double,
        minPredictedBgMgdl: Double?,
        targetBgMgdl: Double,
        highBgPreferenceMgdl: Double,
        tier: HyperSeverityTier = HyperSeverityTier.OFF,
    ): Boolean {
        if (minPredictedBgMgdl == null || !minPredictedBgMgdl.isFinite()) return true
        val dev = bgMgdl - targetBgMgdl
        val band = highBgBandMgdl(targetBgMgdl, highBgPreferenceMgdl)
        if (dev < band * 0.75 && tier < HyperSeverityTier.EMERGING) return true
        if (tier < HyperSeverityTier.EMERGING && dev < band * 0.85) return true
        return minPredictedBgMgdl >= bgMgdl - hypoCredibilityDropMgdl(dev)
    }

    /**
     * Uplifts pathological low terminals used by hypo guard when hyper trajectory is credible.
     */
    fun sanitizeTerminalsForHypoGuard(
        bgMgdl: Double,
        predictedBgMgdl: Double,
        eventualBgMgdl: Double,
        minPredictedBgMgdl: Double?,
        targetBgMgdl: Double,
        highBgPreferenceMgdl: Double,
        scenarioBestTerminalMgdl: Double?,
    ): Pair<Double, Double> {
        val dev = bgMgdl - targetBgMgdl
        val band = highBgBandMgdl(targetBgMgdl, highBgPreferenceMgdl)
        if (dev < band * 0.75) {
            return predictedBgMgdl to eventualBgMgdl
        }
        val floorTerminal = bgMgdl - hypoCredibilityDropMgdl(dev)
        var predicted = predictedBgMgdl
        var eventual = eventualBgMgdl
        if (!isMinPredictedCredible(bgMgdl, minPredictedBgMgdl, targetBgMgdl, highBgPreferenceMgdl)) {
            predicted = max(predicted, floorTerminal)
            eventual = max(eventual, floorTerminal)
        }
        val best = scenarioBestTerminalMgdl?.takeIf { it.isFinite() }
        if (best != null && best > bgMgdl + 40.0) {
            if (eventual < floorTerminal) {
                eventual = max(eventual, minOf(best - 60.0, bgMgdl - 15.0))
            }
            if (predicted < floorTerminal) {
                predicted = max(predicted, minOf(best - 80.0, bgMgdl - 20.0))
            }
        }
        return predicted to eventual
    }
}
