package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.data.configuration.Constants
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

    // 🩸 Réversion endogène anti-absorbante (hybrid/eventual UNIQUEMENT — n'affecte pas IOB/COB/UAM/ZT, donc
    // pas minPredictedAcrossCurves ni la protection hypo). Sans terme de production endogène (EGP), la courbe
    // hybride décline sur 4 h jusqu'au plancher 39 et y reste (plancher absorbant) → eventual = 39 sur ~24 %
    // des ticks alors que le BG réel ne touche jamais l'hypo. Quand l'insuline est épuisée et la courbe sous la
    // baseline, on laisse la contre-régulation ramener l'eventual vers un bas-normal (≈80), pas la remonter haut.
    private const val ENDO_REVERSION_BASELINE_MGDL = 80.0
    private const val ENDO_REVERSION_RATE = 0.06            // fraction du gap comblée par pas de 5 min (lent)
    private const val ENDO_INSULIN_NEGLIGIBLE_MGDL = 0.3    // |impact insuline/pas| en dessous = insuline épuisée

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
        modulation: PredictionPhysioModulation = PredictionPhysioModulation(),
    ): List<Double> =
        predictCurves(
            currentBG = currentBG,
            iobArray = iobArray,
            finalSensitivity = finalSensitivity,
            cobG = cobG,
            profile = profile,
            delta = delta,
            horizonMinutes = horizonMinutes,
            modulation = modulation,
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
        modulation: PredictionPhysioModulation = PredictionPhysioModulation(),
        endogenousReversionEnabled: Boolean = false,
    ): AdvancedPredictionCurves {
        val effectiveHorizonMinutes = maxOf(Constants.PREDICTION_GRAPH_MIN_MINUTES, horizonMinutes)
        val iobSeries = mutableListOf(currentBG)
        val cobSeries = mutableListOf(currentBG)
        val uamSeries = mutableListOf(currentBG)
        val ztSeries = mutableListOf(currentBG)
        val hybridSeries = mutableListOf(currentBG)
        if (effectiveHorizonMinutes <= 0) {
            return AdvancedPredictionCurves(
                iob = iobSeries,
                cob = cobSeries,
                uam = uamSeries,
                zt = ztSeries,
                hybrid = hybridSeries,
            )
        }

        val steps = maxOf(1, effectiveHorizonMinutes / STEP_MINUTES)
        val now = System.currentTimeMillis()
        val carbRatio = profile.carb_ratio.takeIf { it > 0 } ?: 10.0
        val effectiveSensitivity = modulation.effectiveSensitivityMgdlPerU
            .takeIf { it.isFinite() && it > 0.0 }
            ?: finalSensitivity
        val csf = finalSensitivity / carbRatio

        val currentEntry = iobArray.minByOrNull { abs(it.time - now) }
        val currentActivity = currentEntry?.activity ?: 0.0
        val expectedDeltaFromInsulin = -(currentActivity * effectiveSensitivity * STEP_MINUTES.toDouble() * modulation.insulinImpactFactor)
        val initialCarbImpact = if (cobG > 0) {
            ((cobG * csf) / (CARB_ABSORPTION_MINUTES / STEP_MINUTES)) * modulation.carbImpactFactor
        } else {
            0.0
        }
        val expectedDelta = expectedDeltaFromInsulin + initialCarbImpact
        val deviation = delta - expectedDelta
        var hybridMomentum = deviation * modulation.hybridMomentumFactor

        val iobDampingFactor = when {
            delta > 10.0 -> 0.40
            delta > 5.0 -> 0.60
            delta > 2.0 -> 0.80
            else -> 1.0
        }
        val momentumDecay = ((if (delta > 3.0) 0.92 else 0.85) * modulation.momentumDecayFactor)
            .coerceIn(0.72, 0.96)

        var lastIob = currentBG
        var lastCob = currentBG
        var lastUam = currentBG
        var lastZt = currentBG
        var lastHybrid = currentBG
        var uamMomentum = deviation * modulation.uamMomentumFactor

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * STEP_MINUTES
            val targetTime = now + (minutesInFuture * 60_000L)
            val entry = iobArray.minByOrNull { abs(it.time - targetTime) }
            val activityRate = entry?.activity ?: 0.0
            val insulinImpact =
                activityRate * effectiveSensitivity * STEP_MINUTES.toDouble() * modulation.insulinImpactFactor
            val carbImpact = if (cobG > 0 && minutesInFuture < CARB_ABSORPTION_MINUTES) {
                ((cobG * csf) / (CARB_ABSORPTION_MINUTES / STEP_MINUTES)) * modulation.carbImpactFactor
            } else {
                0.0
            }

            lastIob = (lastIob - insulinImpact).coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            lastCob = (lastCob - insulinImpact + carbImpact).coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            lastUam = (lastUam - insulinImpact * iobDampingFactor + uamMomentum)
                .coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            uamMomentum *= momentumDecay
            lastZt = lastIob
            lastHybrid = (lastHybrid - insulinImpact * iobDampingFactor + carbImpact + hybridMomentum)
                .coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            // 🩸 Réversion endogène (EGP) : insuline épuisée + sous baseline → dérive lente vers le bas-normal,
            // pour que l'eventual ne reste pas collé au plancher absorbant 39. Hybrid seul (voir consts).
            if (endogenousReversionEnabled &&
                abs(insulinImpact) < ENDO_INSULIN_NEGLIGIBLE_MGDL &&
                lastHybrid < ENDO_REVERSION_BASELINE_MGDL
            ) {
                lastHybrid = (lastHybrid + (ENDO_REVERSION_BASELINE_MGDL - lastHybrid) * ENDO_REVERSION_RATE)
                    .coerceAtMost(ENDO_REVERSION_BASELINE_MGDL)
            }
            hybridMomentum *= momentumDecay

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
