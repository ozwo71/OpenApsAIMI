package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import kotlin.math.abs

/**
 * Provides a unified prediction model that mirrors the same parameters used during SMB/basal decisions.
 */
object AdvancedPredictionEngine {

    private const val NUMERIC_FLOOR = 39.0
    private const val NUMERIC_CEILING = 401.0
    private const val CARB_ABSORPTION_MINUTES = 180.0
    private const val STEP_MINUTES = 5

    /**
     * Predict the BG evolution using the final ISF/sensitivity applied by the decision engine.
     * Returns the **hybrid** curve (legacy single-path behaviour).
     */
    @JvmStatic
    @JvmOverloads
    fun predict(
        currentBG: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,
        profile: OapsProfileAimi,
        delta: Double = 0.0,
        horizonMinutes: Int = 240,
    ): List<Double> =
        predictCurves(
            currentBG = currentBG,
            iobArray = iobArray,
            finalSensitivity = finalSensitivity,
            cobG = cobG,
            profile = profile,
            delta = delta,
            horizonMinutes = horizonMinutes,
        ).hybrid

    /**
     * Phase 4A — distinct IOB-only, COB+insulin, UAM+momentum, ZT (IOB mirror), and hybrid paths.
     */
    @JvmStatic
    @JvmOverloads
    fun predictCurves(
        currentBG: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,
        profile: OapsProfileAimi,
        delta: Double = 0.0,
        horizonMinutes: Int = 240,
    ): AdvancedPredictionCurves {
        val iobSeries = mutableListOf(currentBG)
        val cobSeries = mutableListOf(currentBG)
        val uamSeries = mutableListOf(currentBG)
        val ztSeries = mutableListOf(currentBG)
        val hybridSeries = mutableListOf(currentBG)
        if (horizonMinutes <= 0) {
            return AdvancedPredictionCurves(
                iob = iobSeries,
                cob = cobSeries,
                uam = uamSeries,
                zt = ztSeries,
                hybrid = hybridSeries,
            )
        }

        val steps = maxOf(1, horizonMinutes / STEP_MINUTES)
        val now = System.currentTimeMillis()
        val carbRatio = profile.carb_ratio.takeIf { it > 0 } ?: 10.0
        val csf = finalSensitivity / carbRatio

        val currentEntry = iobArray.minByOrNull { abs(it.time - now) }
        val currentActivity = currentEntry?.activity ?: 0.0
        val expectedDeltaFromInsulin = -(currentActivity * finalSensitivity * STEP_MINUTES.toDouble())
        val initialCarbImpact = if (cobG > 0) (cobG * csf) / (CARB_ABSORPTION_MINUTES / STEP_MINUTES) else 0.0
        val expectedDelta = expectedDeltaFromInsulin + initialCarbImpact
        val deviation = delta - expectedDelta
        var momentum = deviation

        val iobDampingFactor = when {
            delta > 10.0 -> 0.40
            delta > 5.0 -> 0.60
            delta > 2.0 -> 0.80
            else -> 1.0
        }
        val momentumDecay = if (delta > 3.0) 0.92 else 0.85

        var lastIob = currentBG
        var lastCob = currentBG
        var lastUam = currentBG
        var lastZt = currentBG
        var lastHybrid = currentBG
        var uamMomentum = momentum

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * STEP_MINUTES
            val targetTime = now + (minutesInFuture * 60_000L)
            val entry = iobArray.minByOrNull { abs(it.time - targetTime) }
            val activityRate = entry?.activity ?: 0.0
            val insulinImpact = activityRate * finalSensitivity * STEP_MINUTES.toDouble()
            val carbImpact = if (cobG > 0 && minutesInFuture < CARB_ABSORPTION_MINUTES) {
                (cobG * csf) / (CARB_ABSORPTION_MINUTES / STEP_MINUTES)
            } else {
                0.0
            }

            lastIob = (lastIob - insulinImpact).coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            lastCob = (lastCob - insulinImpact + carbImpact).coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            lastUam = (lastUam - insulinImpact * iobDampingFactor + uamMomentum)
                .coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            uamMomentum *= momentumDecay
            lastZt = lastIob
            lastHybrid = (lastHybrid - insulinImpact * iobDampingFactor + carbImpact + momentum)
                .coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            momentum *= momentumDecay

            iobSeries.add(lastIob)
            cobSeries.add(lastCob)
            uamSeries.add(lastUam)
            ztSeries.add(lastZt)
            hybridSeries.add(lastHybrid)
        }

        return AdvancedPredictionCurves(
            iob = iobSeries,
            cob = cobSeries,
            uam = uamSeries,
            zt = ztSeries,
            hybrid = hybridSeries,
        )
    }
}
