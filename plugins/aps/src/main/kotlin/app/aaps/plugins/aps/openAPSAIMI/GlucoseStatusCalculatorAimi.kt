package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

/** Features additionnels pour AIMI, calculés en même temps que GlucoseStatusSMB. */
data class AimiBgFeatures(
    val accel: Double,              // mg/dL/min^2 ~ via fit quadratique
    val delta5Prev: Double,         // pente 5 min passée (deltaPl)
    val delta5Next: Double,         // pente 5 min future (deltaPn)
    val stable5pctMinutes: Double,  // durée de "bande 5%" (min)
    val corrR2: Double,             // R² du fit retenu
    val combinedDelta: Double,      // delta combiné AIMI (pondérations prefs)
    val isNightGrowthCandidate: Boolean // flag : pente/accel au-dessus des seuils de NG
)

@Reusable
class GlucoseStatusCalculatorAimi @Inject constructor(
    private val log: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val fmt: DecimalFormatter,
    private val deltaCalculator: DeltaCalculator,
    private val preferences: Preferences
) {
    /** Constantes fallback si les prefs ne sont pas dispo (ou désactivées). */
    private object Defaults {
        const val FRESHNESS_MIN = 7L          // comme AutoISF
        const val SERIES_GAP_MIN = 13L        // bande 5%
        const val FIT_GAP_MIN = 11L           // fit quad
        const val CUTOFF_MIN = 47.5           // horizon fit
        const val BW_FRACTION = 0.05          // ±5% bande stabilisée
    }

    data class Result(
        val gs: GlucoseStatusSMB?,
        val features: AimiBgFeatures?
    )

    /**
     * Calcule un GlucoseStatusSMB + features AIMI.
     * - GlucoseStatusSMB = compatible avec le pipeline SMB standard
     * - Features = métriques avancées pour AIMI (accélération, combinedDelta, NG flag, etc.)
     */
    fun compute(allowOldData: Boolean): Result {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return Result(null, null)
        if (data.isEmpty()) return Result(null, null)

        val now = dateUtil.now()
        if (data[0].timestamp < now - Defaults.FRESHNESS_MIN * 60 * 1000L && !allowOldData) {
            log.debug(LTag.GLUCOSE, "AIMI GS: old data")
            return Result(null, null)
        }

        // 1) Deltas standard (SMB)
        val deltas = deltaCalculator.calculateDeltas(data)

        // 2) Série "bande 5%" (durée de stabilité relative)
        val bw = Defaults.BW_FRACTION
        val head = data[0]
        var sum = head.recalculated
        var avg = sum
        var minutesDur = 0.0
        var n = 1
        val headTs = head.timestamp
        for (i in 1 until data.size) {
            val rec = data[i]
            if (rec.value <= 39 || rec.filledGap) continue
            val gapMin = ((headTs - rec.timestamp) / 60000.0)
            val within = rec.recalculated > avg * (1 - bw) && rec.recalculated < avg * (1 + bw)
            // stop si gap trop grand (13 min) ou on sort de la bande
            if (gapMin - minutesDur > Defaults.SERIES_GAP_MIN || !within) break
            n++
            sum += rec.recalculated
            avg = sum / n
            minutesDur = gapMin
        }

        // 3) Fit quadratique "à la AutoISF" (accélération, deltas ±5 min, R²)
        val fit = QuadraticFit.fit5min(data,
                                       cutoffMin = Defaults.CUTOFF_MIN,
                                       fitGapMin = Defaults.FIT_GAP_MIN
        )

        // 4) Combined delta AIMI depuis prefs (si présentes)
        val wDelta = 1.0                           // base
        val wShort = preferences.getOr( DoubleKey.OApsAIMIAutodriveAcceleration, 1.0 ) // on recycle comme pondération "short"
        val wLong  = preferences.getOr( DoubleKey.OApsAIMIcombinedDelta,       1.0 )   // et comme pondération "long"
        val combinedDelta = combineDelta(
            deltas.delta, deltas.shortAvgDelta, deltas.longAvgDelta,
            wDelta, wShort, wLong
        )

        // 5) Flag Night-Growth : utilise ton seuil AIMI
        val ngrSlopeMin = preferences.getOr(DoubleKey.OApsAIMINightGrowthMinRiseSlope, 5.0) // mg/dL/5min
        val isNg = (fit?.deltaPl ?: 0.0) >= ngrSlopeMin || (fit?.accel ?: 0.0) > 0.0

        val gs = GlucoseStatusSMB(
            glucose = head.recalculated,
            noise = 0.0,
            delta = deltas.delta,
            shortAvgDelta = deltas.shortAvgDelta,
            longAvgDelta = deltas.longAvgDelta,
            date = head.timestamp
        )

        val features = AimiBgFeatures(
            accel = fit?.accel ?: 0.0,
            delta5Prev = fit?.deltaPl ?: 0.0,
            delta5Next = fit?.deltaPn ?: 0.0,
            stable5pctMinutes = minutesDur,
            corrR2 = fit?.r2 ?: 0.0,
            combinedDelta = combinedDelta,
            isNightGrowthCandidate = isNg
        )

        log.debug(LTag.GLUCOSE, "AIMI GS: g=${head.recalculated} Δ=${fmt.to1Decimal(deltas.delta)} SA=${fmt.to1Decimal(deltas.shortAvgDelta)} LA=${fmt.to1Decimal(deltas.longAvgDelta)} acc=${fmt.to2Decimal(features.accel)} combΔ=${fmt.to2Decimal(features.combinedDelta)} NG=$isNg")
        return Result(gs, features)
    }

    /** Petite extension util pour lire avec valeur par défaut. */
    private fun Preferences.getOr(key: DoubleKey, default: Double): Double =
        runCatching { this.get(key) }.getOrNull() ?: default

    /** Pondération simple (normalisée) des deltas. */
    private fun combineDelta(d: Double, s: Double, l: Double, wD: Double, wS: Double, wL: Double): Double {
        val sumW = max(1e-9, wD + wS + wL)
        return (wD * d + wS * s + wL * l) / sumW
    }
}

/** Fit quadratique local pour extraire accel / deltaPl / deltaPn / R². */
private object QuadraticFit {
    data class Out(
        val accel: Double,  // mg/dL/min^2
        val deltaPl: Double,// pente 5 min passée
        val deltaPn: Double,// pente 5 min future
        val r2: Double
    )

    fun fit5min(
        data: List<app.aaps.core.data.model.BT>, // Bucketed table row type (adapter si nom différent)
        cutoffMin: Double,
        fitGapMin: Long
    ): Out? {
        if (data.size < 4) return null
        val t0 = data[0].timestamp
        var lastTi = 0.0
        var n = 0

        var sumY = 0.0
        var sumX = 0.0
        var sumX2 = 0.0
        var sumX3 = 0.0
        var sumX4 = 0.0
        var sumXY = 0.0
        var sumX2Y = 0.0

        var best: Out? = null
        var bestR2 = -1.0

        val SCALE_TIME = 300.0  // 5 min en secondes
        val SCALE_BG = 50.0

        for (i in data.indices) {
            val r = data[i]
            if (r.recalculated <= 39 || r.filledGap) continue
            n++

            val ti = (r.timestamp - t0) / 1000.0 / SCALE_TIME
            if (-ti * SCALE_TIME > cutoffMin * 60) break
            if (ti < lastTi - fitGapMin * 60 / SCALE_TIME) break
            lastTi = ti

            val x = ti
            val y = r.recalculated / SCALE_BG

            val x2 = x * x
            val x3 = x2 * x
            val x4 = x2 * x2

            sumX += x; sumX2 += x2; sumX3 += x3; sumX4 += x4
            sumY += y; sumXY += x * y; sumX2Y += x2 * y

            if (n > 3) {
                val detH = sumX4 * (sumX2 * n - sumX * sumX) -
                    sumX3 * (sumX3 * n - sumX * sumX2) +
                    sumX2 * (sumX3 * sumX - sumX2 * sumX2)
                if (detH == 0.0) continue

                val detA = sumX2Y * (sumX2 * n - sumX * sumX) -
                    sumXY  * (sumX3 * n - sumX * sumX2) +
                    sumY   * (sumX3 * sumX - sumX2 * sumX2)
                val detB = sumX4 * (sumXY * n - sumY * sumX) -
                    sumX3 * (sumX2Y * n - sumY * sumX2) +
                    sumX2 * (sumX2Y * sumX - sumXY * sumX2)
                val detC = sumX4 * (sumX2 * sumY - sumX * sumXY) -
                    sumX3 * (sumX3 * sumY - sumX * sumX2Y) +
                    sumX2 * (sumX3 * sumXY - sumX2 * sumX2Y)

                val a = detA / detH
                val b = detB / detH
                val c = detC / detH

                // R²
                var ssTot = 0.0
                var ssRes = 0.0
                val yMean = sumY / n
                for (j in 0..i) {
                    val rj = data[j]
                    val xj = (rj.timestamp - t0) / 1000.0 / SCALE_TIME
                    val yj = rj.recalculated / SCALE_BG
                    val yHat = a * xj * xj + b * xj + c
                    val d = yj - yHat
                    ssTot += (yj - yMean) * (yj - yMean)
                    ssRes += d * d
                }
                val r2 = if (ssTot != 0.0) 1 - ssRes / ssTot else 0.0

                // Slope ±5 min et accel en unités mg/dL/min^2
                val dt = 5 * 60 / SCALE_TIME
                val accel = 2 * a * SCALE_BG / 60.0 // par s, converti min^2 -> ici simplifié (cohérence interne)
                val deltaPl = -SCALE_BG * (a * (-dt) * (-dt) - b * dt)
                val deltaPn =  SCALE_BG * (a * ( dt) * ( dt) + b * dt)

                if (r2 >= bestR2) {
                    bestR2 = r2
                    best = Out(accel = accel, deltaPl = deltaPl, deltaPn = deltaPn, r2 = r2)
                }
            }
        }
        return best
    }
}
