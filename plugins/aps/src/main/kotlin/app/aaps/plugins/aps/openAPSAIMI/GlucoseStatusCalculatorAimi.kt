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
import kotlin.math.max

/** Features additionnels pour AIMI, calculés en même temps que GlucoseStatusSMB. */
data class AimiBgFeatures(
    // Fit quadratique / dynamique
    val accel: Double,               // mg/dL/min^2
    val delta5Prev: Double,          // pente 5 min passée (deltaPl)
    val delta5Next: Double,          // pente 5 min future (deltaPn)
    val corrR2: Double,              // R² du fit retenu
    val parabolaMinutes: Double,     // durée couverte par le fit (minutes)
    val a0: Double,                  // coefficients de la parabole (BG = a0 + a1*t + a2*t^2), t en pas de 5 min
    val a1: Double,
    val a2: Double,

    // Bande ±5 %
    val stable5pctMinutes: Double,   // durée de "bande 5%" (min)
    val stable5pctAverage: Double,       // moyenne BG sur la bande ±5 %

    // Indicateurs AIMI
    val combinedDelta: Double,       // delta combiné AIMI (pondérations prefs)
    val isNightGrowthCandidate: Boolean
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
        const val FRESHNESS_MIN = 7L          // données CGM max âgées de 7 min
        const val SERIES_GAP_MIN = 13L        // gap max en min dans la série bande ±5 %
        const val FIT_GAP_MIN = 11L           // gap max en min pour le fit quad
        const val CUTOFF_MIN = 47.5           // horizon d’analyse max (minutes)
        const val BW_FRACTION = 0.05          // ±5% bande stabilisée
        const val CACHE_TTL_MS = 30_000L      // 30s de TTL pour le cache local
    }

    data class Result(
        val gs: GlucoseStatusSMB?,     // pour le pipeline standard
        val features: AimiBgFeatures?  // pour AIMI (accel, deltaPl, deltaPn, a0/a1/a2…)
    )

    private var last: Result? = null
    private var lastTs: Long = 0L

    /** API “standard” attendue par le plugin: renvoie un GlucoseStatus (SMB). */
    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusSMB? =
        (if (shouldRecompute(allowOldData)) compute(allowOldData) else last)?.gs

    /** API AIMI: renvoie les features (accélération, deltas paraboliques, etc.). */
    fun getAimiFeatures(allowOldData: Boolean): AimiBgFeatures? =
        (if (shouldRecompute(allowOldData)) compute(allowOldData) else last)?.features

    /** Renvoie le couple SMB + features, recalcule si besoin. */
    fun compute(allowOldData: Boolean): Result {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy()
        if (data == null || data.isEmpty()) {
            log.debug(LTag.GLUCOSE, "AIMI GS: no data")
            return storeAndReturn(null, null)
        }

        val now = dateUtil.now()
        if (data[0].timestamp < now - Defaults.FRESHNESS_MIN * 60 * 1000L && !allowOldData) {
            log.debug(LTag.GLUCOSE, "AIMI GS: old data")
            return storeAndReturn(null, null)
        }

        // 1) Deltas standard (SMB)
        val deltas = deltaCalculator.calculateDeltas(data)

        // 2) Bande ±5 %
        val bw = Defaults.BW_FRACTION
        val head = data[0]
        val headTs = head.timestamp
        var sum = head.recalculated
        var avg = sum
        var minutesDur = 0.0
        var n = 1
        for (i in 1 until data.size) {
            val rec = data[i]
            if (rec.value <= 39 || rec.filledGap) continue
            val gapMin = (headTs - rec.timestamp) / 60000.0
            val within = rec.recalculated > avg * (1 - bw) && rec.recalculated < avg * (1 + bw)
            if (gapMin - minutesDur > Defaults.SERIES_GAP_MIN || !within) break
            n++
            sum += rec.recalculated
            avg = sum / n
            minutesDur = gapMin
        }

        // 3) Fit quadratique local (accélération, deltas ±5 min, R², a0/a1/a2, durée du fit)
        val fit = QuadraticFit.fit5min(
            data = data,
            cutoffMin = Defaults.CUTOFF_MIN,
            fitGapMin = Defaults.FIT_GAP_MIN,
            time = { it.timestamp },
            recalc = { it.recalculated },
            filled = { it.filledGap }
        )

        // 4) Delta combiné (pondérations depuis les prefs)
        //    - on recycle deux clés existantes pour les poids (sinon 1.0 par défaut)
        val wDelta = 1.0
        val wShort = preferences.getOr(DoubleKey.OApsAIMIAutodriveAcceleration, 1.0) // poids "short"
        val wLong  = preferences.getOr(DoubleKey.OApsAIMIcombinedDelta, 1.0)         // poids "long"
        val combinedDelta = combineDelta(
            d = deltas.delta,
            s = deltas.shortAvgDelta,
            l = deltas.longAvgDelta,
            wD = wDelta, wS = wShort, wL = wLong
        )

        // 5) Heuristique Night-Growth
        val ngrSlopeMin = preferences.getOr(DoubleKey.OApsAIMINightGrowthMinRiseSlope, 5.0) // mg/dL/5min
        val isNg = (fit?.deltaPl ?: 0.0) >= ngrSlopeMin || (fit?.accel ?: 0.0) > 0.0

        // 6) Construction GlucoseStatusSMB + features AIMI
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
            corrR2 = fit?.r2 ?: 0.0,
            parabolaMinutes = fit?.duraMin ?: 0.0,
            a0 = fit?.a0 ?: 0.0,
            a1 = fit?.a1 ?: 0.0,
            a2 = fit?.a2 ?: 0.0,

            stable5pctMinutes = minutesDur,
            stable5pctAverage = avg,

            combinedDelta = combinedDelta,
            isNightGrowthCandidate = isNg
        )

        log.debug(
            LTag.GLUCOSE,
            "AIMI GS: g=${head.recalculated} " +
                "Δ=${fmt.to1Decimal(deltas.delta)} " +
                "SA=${fmt.to1Decimal(deltas.shortAvgDelta)} " +
                "LA=${fmt.to1Decimal(deltas.longAvgDelta)} " +
                "acc=${fmt.to2Decimal(features.accel)} " +
                "combΔ=${fmt.to2Decimal(features.combinedDelta)} " +
                "R2=${fmt.to2Decimal(features.corrR2)} " +
                "fitMin=${fmt.to1Decimal(features.parabolaMinutes)} " +
                "NG=$isNg"
        )

        return storeAndReturn(gs, features)
    }

    // --- Helpers internes ----------------------------------------------------

    private fun storeAndReturn(gs: GlucoseStatusSMB?, features: AimiBgFeatures?): Result {
        val res = Result(gs, features)
        last = res
        lastTs = dateUtil.now()
        return res
    }

    private fun shouldRecompute(allowOldData: Boolean): Boolean {
        if (last == null) return true
        val staleness = dateUtil.now() - lastTs
        // Recalcule si on ne veut pas de vieux data, si cache périmé, ou si jamais calculé
        return (!allowOldData) || staleness > Defaults.CACHE_TTL_MS
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

/** Fit quadratique local pour extraire accel / deltaPl / deltaPn / R² / a0/a1/a2 / durée. */
private object QuadraticFit {

    data class Out(
        val accel: Double,   // mg/dL/min^2
        val deltaPl: Double, // pente 5 min passée
        val deltaPn: Double, // pente 5 min future
        val r2: Double,
        val duraMin: Double, // durée couverte par le fit (minutes)
        val a0: Double,
        val a1: Double,
        val a2: Double
    )

    fun <T> fit5min(
        data: List<T>,
        cutoffMin: Double,
        fitGapMin: Long,
        time: (T) -> Long,
        recalc: (T) -> Double,
        filled: (T) -> Boolean
    ): Out? {
        if (data.size < 4) return null

        val SCALE_TIME = 300.0  // 5 min en secondes
        val SCALE_BG = 50.0

        val t0 = time(data[0])
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

        for (i in data.indices) {
            val r = data[i]
            if (recalc(r) <= 39 || filled(r)) continue
            n++

            val ti = (time(r) - t0) / 1000.0 / SCALE_TIME
            if (-ti * SCALE_TIME > cutoffMin * 60) break
            if (ti < lastTi - fitGapMin * 60 / SCALE_TIME) break
            lastTi = ti

            val x = ti
            val y = recalc(r) / SCALE_BG

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
                    val xj = (time(rj) - t0) / 1000.0 / SCALE_TIME
                    val yj = recalc(rj) / SCALE_BG
                    val yHat = a * xj * xj + b * xj + c
                    val d = yj - yHat
                    ssTot += (yj - yMean) * (yj - yMean)
                    ssRes += d * d
                }
                val r2 = if (ssTot != 0.0) 1 - ssRes / ssTot else 0.0

                // Slopes ±5 min & acceleration
                val dt = 5 * 60 / SCALE_TIME
                val accel = 2 * a * SCALE_BG / 60.0        // mg/dL/min^2
                val deltaPl = -SCALE_BG * (a * (-dt) * (-dt) - b * dt)
                val deltaPn =  SCALE_BG * (a * ( dt) * ( dt) + b * dt)

                // Coeffs remis en échelle (t en pas de 5 min)
                val a0 = c * SCALE_BG
                val a1 = b * SCALE_BG
                val a2 = a * SCALE_BG

                if (r2 >= bestR2) {
                    val duraMin = (-ti * (SCALE_TIME / 60.0)) * 5.0 // minutes couvertes
                    bestR2 = r2
                    best = Out(
                        accel = accel,
                        deltaPl = deltaPl,
                        deltaPn = deltaPn,
                        r2 = r2,
                        duraMin = kotlin.math.max(0.0, duraMin),
                        a0 = a0,
                        a1 = a1,
                        a2 = a2
                    )
                }
            }
        }
        return best
    }
}
