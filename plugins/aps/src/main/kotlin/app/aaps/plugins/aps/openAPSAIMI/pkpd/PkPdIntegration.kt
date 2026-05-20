package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class MealAggressionContext(
    val mealModeActive: Boolean,
    val predictedBgMgdl: Double? = null,
    val targetBgMgdl: Double? = null
)

data class PkpdBolusSample(
    val ageMin: Double,
    val units: Double
)

class PkPdIntegration(private val preferences: Preferences) {

    companion object {
        /**
         * Minimum change in learned peak (minutes vs last persisted) required to write prefs again.
         * Tight values cause noisy UI; TAP-G RFC §9 B.3 — tune with product if needed.
         */
        const val PEAK_PERSIST_MIN_DELTA_MIN = 0.5
    }

    private data class Config(
        val enabled: Boolean,
        val bounds: PkPdBounds,
        val initial: PkPdParams,
        val isfBounds: IsfFusionBounds,
        val tailPolicy: TailAwareSmbPolicy
    )

    private var cachedConfig: Config? = null
    private var estimator: AdaptivePkPdEstimator? = null
    private var lastBounds: PkPdBounds? = null
    private var fusion: IsfFusion? = null
    private var lastFusionBounds: IsfFusionBounds? = null
    private var damping: SmbDamping? = null
    private var lastTailPolicy: TailAwareSmbPolicy? = null
    private var lastPersisted: PkPdParams? = null
    private var recentBolusSamples: List<PkpdBolusSample> = emptyList()

    @Synchronized
    fun setRecentBolusSamples(samples: List<PkpdBolusSample>) {
        recentBolusSamples = samples
            .asSequence()
            .filter { it.units > 0.0 && it.ageMin.isFinite() && it.ageMin >= 0.0 }
            .toList()
    }
    @Synchronized
    fun reconstructedIobUnits(): Double {
        val est = estimator ?: return 0.0
        return recentBolusSamples.sumOf { sample ->
            sample.units * est.iobResidualAt(sample.ageMin)
        }.coerceAtLeast(0.0)
    }

    @Synchronized
    fun computeRuntime(
        epochMillis: Long,
        bg: Double,
        deltaMgDlPer5: Double,
        iobU: Double,
        carbsActiveG: Double,
        windowMin: Int,
        exerciseFlag: Boolean,
        profileIsf: Double,
        tdd24h: Double,
        mealContext: MealAggressionContext? = null,
        consoleLog: MutableList<String>? = null,
        combinedDelta: Double? = null,
        uamConfidence: Double = 0.0
    ): PkPdRuntime? {
        val config = readConfig()
        // If the configuration changed, clear cached objects so they are rebuilt
        if (cachedConfig == null || cachedConfig != config) {
            cachedConfig = config
            estimator = null
            fusion = null
            damping = null
            lastBounds = null
            lastFusionBounds = null
            lastTailPolicy = null
            lastPersisted = null
        }
        if (!config.enabled) {
            consoleLog?.add("PKPD Debug: Config ENABLED is FALSE. Check OApsAIMIPkpdEnabled preference.")
            // When disabled we also clear caches

            estimator = null
            fusion = null
            damping = null
            lastBounds = null
            lastFusionBounds = null
            lastTailPolicy = null
            cachedConfig = null
            lastPersisted = null
            return null
        }

        if (lastPersisted == null) {
            lastPersisted = clampParams(config.initial, config.bounds)
        }

        // Objects are created lazily; ensure* will reuse existing instances when possible
        val estimator = ensureEstimator(config)
        val fusion = ensureFusion(config.isfBounds)
        val damping = ensureDamping(config.tailPolicy)
        val tddIsf = computeTddIsf(tdd24h, profileIsf)
        IsfTddProvider.set(tddIsf)
        val epochMin = TimeUnit.MILLISECONDS.toMinutes(epochMillis)
        val learningWindowMin = windowMin.coerceIn(
            AdaptivePkPdEstimator.LEARNING_WINDOW_MIN_MIN,
            AdaptivePkPdEstimator.LEARNING_WINDOW_MAX_MIN,
        )
        if (learningWindowMin != windowMin) {
            consoleLog?.add(
                "PKPD_LEARN: windowMin=$windowMin clamped to $learningWindowMin for estimator.update " +
                    "(bounds ${AdaptivePkPdEstimator.LEARNING_WINDOW_MIN_MIN}-${AdaptivePkPdEstimator.LEARNING_WINDOW_MAX_MIN})",
            )
        }
        logPkpdLearningSkipReason(iobU, carbsActiveG, deltaMgDlPer5, exerciseFlag, consoleLog)
        estimator.update(
            epochMin = epochMin,
            bg = bg,
            deltaMgDlPer5 = deltaMgDlPer5,
            iobU = iobU,
            carbsActiveG = carbsActiveG,
            windowMin = learningWindowMin,
            exerciseFlag = exerciseFlag
        )
        val params = estimator.params()
        persistStateIfNeeded(params, config.bounds)
        val tailFraction = estimator.iobResidualAt(windowMin.toDouble()).coerceIn(0.0, 1.0)
        val baselineActivityState = estimator.activityStateAt(windowMin.toDouble())
        val activityState = aggregateActivityState(
            estimator = estimator,
            baseline = baselineActivityState,
            iobU = iobU
        )
        val freshness = (1.0 - activityState.postWindowFraction).coerceIn(0.0, 1.0)
        val activityBlend = (0.6 * activityState.relativeActivity + 0.4 * freshness).coerceIn(0.0, 1.0)
        val anticipatoryBoost = activityState.anticipationWeight * 0.1
        val mealBoost = mealContext?.let { ctx ->
            if (!ctx.mealModeActive) return@let 0.0
            val predicted = ctx.predictedBgMgdl
            val target = ctx.targetBgMgdl
            val normalizedRise = if (predicted != null && target != null) {
                ((predicted - target).coerceAtLeast(0.0) / 70.0).coerceIn(0.0, 1.0)
            } else 0.0
            0.05 + 0.15 * normalizedRise
        } ?: 0.0
        val minScale = if (mealContext?.mealModeActive == true) 0.9 else 0.8
        val maxScale = if (mealContext?.mealModeActive == true) 1.5 else 1.4
        val pkpdScale = (1.0 + 0.12 * tailFraction + 0.22 * activityBlend + anticipatoryBoost + mealBoost)
            .coerceIn(minScale, maxScale)
            
        // 🚀 CONFIRMATION DE MONTÉE : Priorité au combinedDelta si disponible
        val effectiveDelta = combinedDelta ?: deltaMgDlPer5
        val isRising = effectiveDelta > 0.5 // Seuil de montée pour verrouiller l'agression
        
        // 🚀 VELOCITY BOOST : Increase aggression (reduce ISF) based on effectiveDelta
        var aggressionMultiplier = if (effectiveDelta > 1.5) {
            val rawFactor = Math.exp(-0.04 * (effectiveDelta - 1.5))
            rawFactor.coerceIn(0.60, 1.0)
        } else 1.0

        // 🧠 UAM BRAIN BOOST : If ML detects a meal, force more aggression
        if (uamConfidence > 0.5) {
            val uamBoost = 1.0 - (uamConfidence - 0.5) * 0.4 // extra up to 20% reduction
            aggressionMultiplier *= uamBoost.coerceIn(0.8, 1.0)
            consoleLog?.add("🧠 UAM detected (conf=${"%.2f".format(uamConfidence)}) -> Extra ISF Boost")
        }
        
        val fusedIsf = fusion.fused(profileIsf, tddIsf, pkpdScale, isRising, aggressionMultiplier)
        return PkPdRuntime(
            params = params,
            tailFraction = tailFraction,
            fusedIsf = fusedIsf,
            profileIsf = profileIsf,
            tddIsf = tddIsf,
            pkpdScale = pkpdScale,
            damping = damping,
            activity = activityState
        )
    }

    private fun aggregateActivityState(
        estimator: AdaptivePkPdEstimator,
        baseline: InsulinActivityState,
        iobU: Double
    ): InsulinActivityState {
        val samples = recentBolusSamples
        if (samples.isEmpty()) return baseline

        val weightsByStage = linkedMapOf<InsulinActivityStage, Double>()
        var totalWeight = 0.0
        var weightedRelative = 0.0
        var weightedPosition = 0.0
        var weightedPostWindow = 0.0
        var weightedAnticipation = 0.0
        var weightedMinutesToOnset = 0.0

        samples.forEach { sample ->
            val bolusState = estimator.activityStateAt(sample.ageMin)
            val residualIob = estimator.iobResidualAt(sample.ageMin)
            val baseWeight = (sample.units * residualIob).coerceAtLeast(0.0)
            if (baseWeight <= 1e-6) return@forEach

            val stageWeight = (baseWeight * bolusState.relativeActivity.coerceAtLeast(0.05)).coerceAtLeast(1e-6)
            totalWeight += stageWeight
            weightsByStage[bolusState.stage] = (weightsByStage[bolusState.stage] ?: 0.0) + stageWeight
            weightedRelative += bolusState.relativeActivity * stageWeight
            weightedPosition += bolusState.normalizedPosition * stageWeight
            weightedPostWindow += bolusState.postWindowFraction * stageWeight
            weightedAnticipation += bolusState.anticipationWeight * stageWeight
            weightedMinutesToOnset += bolusState.minutesUntilOnset * stageWeight
        }

        if (totalWeight <= 1e-6) return baseline

        val dominantStage = weightsByStage.maxByOrNull { it.value }?.key ?: baseline.stage
        val coherentStage = if (dominantStage == InsulinActivityStage.PRE_ONSET && iobU >= 3.0) {
            InsulinActivityStage.RISING
        } else {
            dominantStage
        }

        return baseline.copy(
            relativeActivity = (weightedRelative / totalWeight).coerceIn(0.0, 1.0),
            normalizedPosition = (weightedPosition / totalWeight).coerceIn(0.0, 1.0),
            postWindowFraction = (weightedPostWindow / totalWeight).coerceIn(0.0, 1.0),
            anticipationWeight = (weightedAnticipation / totalWeight).coerceIn(0.0, 1.0),
            minutesUntilOnset = (weightedMinutesToOnset / totalWeight).coerceAtLeast(0.0),
            stage = coherentStage
        )
    }

    private fun readConfig(): Config {
        val enabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled)
        val bounds = PkPdBounds(
            diaMinH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
            diaMaxH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
            peakMinMin = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
            peakMinMax = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
            maxDiaChangePerDayH = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH),
            maxPeakChangePerDayMin = preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin)
        )
        val initial = PkPdParams(
            diaHrs = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH),
            peakMin = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin)
        )
        val isfBounds = IsfFusionBounds(
            minFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            maxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
            maxChangePer5Min = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)
        )
        val tailPolicy = TailAwareSmbPolicy(
            tailIobHigh = preferences.get(DoubleKey.OApsAIMISmbTailThreshold),
            smbDampingAtTail = preferences.get(DoubleKey.OApsAIMISmbTailDamping),
            postExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping),
            lateFattyMealDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping)
        )
        return Config(enabled, bounds, initial, isfBounds, tailPolicy)
    }

    private fun ensureEstimator(config: Config): AdaptivePkPdEstimator {
        val learningCfg = PkPdLearningConfig(bounds = config.bounds)
        // Re‑create estimator only when we have never created one or the bounds changed
        if (estimator == null || lastBounds != config.bounds) {
            val start = estimator?.params()?.let { clampParams(it, config.bounds) }
                ?: clampParams(lastPersisted ?: config.initial, config.bounds)
            estimator = AdaptivePkPdEstimator(LogNormalKernel(), learningCfg, start)
            lastBounds = config.bounds
        }
        return estimator!!
    }

    private fun ensureFusion(bounds: IsfFusionBounds): IsfFusion {
        if (fusion == null || lastFusionBounds != bounds) {
            fusion = IsfFusion(bounds)
            lastFusionBounds = bounds
        }
        return fusion!!
    }

    private fun ensureDamping(policy: TailAwareSmbPolicy): SmbDamping {
        if (damping == null || lastTailPolicy != policy) {
            damping = SmbDamping(policy)
            lastTailPolicy = policy
        }
        return damping!!
    }

    private fun clampParams(params: PkPdParams, bounds: PkPdBounds): PkPdParams {
        val dia = params.diaHrs.coerceIn(bounds.diaMinH, bounds.diaMaxH)
        val peak = params.peakMin.coerceIn(bounds.peakMinMin, bounds.peakMinMax)
        return PkPdParams(dia, peak)
    }

    private fun persistStateIfNeeded(params: PkPdParams, bounds: PkPdBounds) {
        val clamped = clampParams(params, bounds)
        val last = lastPersisted
        val shouldPersist = last == null ||
            abs(last.diaHrs - clamped.diaHrs) > 0.01 ||
            abs(last.peakMin - clamped.peakMin) > PEAK_PERSIST_MIN_DELTA_MIN
        if (shouldPersist) {
            preferences.put(DoubleKey.OApsAIMIPkpdStateDiaH, clamped.diaHrs)
            preferences.put(DoubleKey.OApsAIMIPkpdStatePeakMin, clamped.peakMin)
            lastPersisted = clamped
        }
    }

    private fun logPkpdLearningSkipReason(
        iobU: Double,
        carbsActiveG: Double,
        deltaMgDlPer5: Double,
        exerciseFlag: Boolean,
        consoleLog: MutableList<String>?,
    ) {
        val reason = when {
            iobU < 0.3 -> "iob<0.3"
            carbsActiveG > 15.0 -> "cob>15"
            exerciseFlag -> "exercise"
            deltaMgDlPer5 > 5.0 -> "delta>5"
            else -> null
        }
        if (reason != null) {
            consoleLog?.add("PKPD_LEARN skip: $reason (update() returns early in estimator)")
        }
    }

    private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
        if (tdd24h <= 0.1) return fallback
        val anchored = 1800.0 / tdd24h
        
        // 🛡️ CLAMP: Prevent TDD-ISF from deviating more than ±50% from profile
        // Protects against temporary TDD anomalies (new site, atypical day, etc.)
        // Example: Profile ISF = 147, TDD-ISF raw = 57 → clamped to 73.5
        val maxDeviation = fallback * 0.5
        val clamped = anchored.coerceIn(
            fallback - maxDeviation,  // Min: profile × 0.5
            fallback + maxDeviation   // Max: profile × 1.5
        )
        
        return clamped.coerceIn(5.0, 400.0)
    }
}

class PkPdRuntime(
    val params: PkPdParams,
    val tailFraction: Double,
    val fusedIsf: Double,
    val profileIsf: Double,
    val tddIsf: Double,
    val pkpdScale: Double,
    private val damping: SmbDamping,
    val activity: InsulinActivityState
) {

    // ✅ API audit (garde)
    fun dampSmbWithAudit(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false
    ): SmbDampingAudit =
        damping.dampWithAudit(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity)

    // ✅ API non-audit (garde) — utile si on veut le résultat sans traces
    fun dampSmb(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false
    ): Double =
        damping.damp(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity)
}