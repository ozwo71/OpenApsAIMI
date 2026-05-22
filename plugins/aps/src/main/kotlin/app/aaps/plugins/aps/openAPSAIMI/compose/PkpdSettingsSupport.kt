package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.advisor.PkpdPrefsSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import kotlin.math.abs

/** Maps a single 0–1 “correction prudence” slider to ISF fusion bounds. */
object PkpdCorrectionPrudence {
    private const val MIN_FACTOR_PRUDENT = 0.85
    private const val MAX_FACTOR_PRUDENT = 1.10
    private const val MIN_FACTOR_NEUTRAL = 0.75
    private const val MAX_FACTOR_NEUTRAL = 1.25
    private const val MIN_FACTOR_AGGRESSIVE = 0.65
    private const val MAX_FACTOR_AGGRESSIVE = 1.40

    fun readLevel(preferences: Preferences): Double {
        val min = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor)
        val max = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor)
        val neutralMin = MIN_FACTOR_NEUTRAL
        val neutralMax = MAX_FACTOR_NEUTRAL
        val minDelta = abs(min - neutralMin)
        val maxDelta = abs(max - neutralMax)
        val prudenceScore = (minDelta + maxDelta) / 2.0
        return when {
            prudenceScore < 0.06 -> 0.5
            min >= MIN_FACTOR_PRUDENT - 0.02 && max <= MAX_FACTOR_PRUDENT + 0.02 -> 0.0
            min <= MIN_FACTOR_AGGRESSIVE + 0.02 && max >= MAX_FACTOR_AGGRESSIVE - 0.02 -> 1.0
            prudenceScore < 0.12 -> 0.25
            else -> 0.75
        }.coerceIn(0.0, 1.0)
    }

    fun applyLevel(preferences: Preferences, level: Double) {
        val t = level.coerceIn(0.0, 1.0)
        val minFactor = when {
            t <= 0.5 -> lerp(MIN_FACTOR_PRUDENT, MIN_FACTOR_NEUTRAL, t * 2.0)
            else -> lerp(MIN_FACTOR_NEUTRAL, MIN_FACTOR_AGGRESSIVE, (t - 0.5) * 2.0)
        }
        val maxFactor = when {
            t <= 0.5 -> lerp(MAX_FACTOR_PRUDENT, MAX_FACTOR_NEUTRAL, t * 2.0)
            else -> lerp(MAX_FACTOR_NEUTRAL, MAX_FACTOR_AGGRESSIVE, (t - 0.5) * 2.0)
        }
        preferences.put(DoubleKey.OApsAIMIIsfFusionMinFactor, minFactor.coerceIn(0.5, 1.0))
        preferences.put(DoubleKey.OApsAIMIIsfFusionMaxFactor, maxFactor.coerceIn(1.0, 2.0))
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}

/** Maps a 0–1 slider to SMB tail damping (higher slider = more tail delivery). */
object PkpdTailPrudence {
    private const val DAMPING_CAUTIOUS = 0.92
    private const val DAMPING_NEUTRAL = 0.85
    private const val DAMPING_PERMISSIVE = 0.70

    fun readLevel(preferences: Preferences): Double {
        val damping = preferences.get(DoubleKey.OApsAIMISmbTailDamping)
        return when {
            damping >= DAMPING_CAUTIOUS - 0.02 -> 0.0
            damping <= DAMPING_PERMISSIVE + 0.02 -> 1.0
            abs(damping - DAMPING_NEUTRAL) < 0.04 -> 0.5
            damping > DAMPING_NEUTRAL -> 0.25
            else -> 0.75
        }.coerceIn(0.0, 1.0)
    }

    fun applyLevel(preferences: Preferences, level: Double) {
        val t = level.coerceIn(0.0, 1.0)
        val damping = when {
            t <= 0.5 -> lerp(DAMPING_CAUTIOUS, DAMPING_NEUTRAL, t * 2.0)
            else -> lerp(DAMPING_NEUTRAL, DAMPING_PERMISSIVE, (t - 0.5) * 2.0)
        }
        preferences.put(
            DoubleKey.OApsAIMISmbTailDamping,
            damping.coerceIn(DoubleKey.OApsAIMISmbTailDamping.min, DoubleKey.OApsAIMISmbTailDamping.max),
        )
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}

enum class PkpdLearningPace {
    SLOW,
    NORMAL,
    FAST,
}

fun PkpdLearningPace.readFrom(preferences: Preferences): PkpdLearningPace {
    val dia = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH)
    return when {
        dia <= 0.3 -> PkpdLearningPace.SLOW
        dia >= 0.8 -> PkpdLearningPace.FAST
        else -> PkpdLearningPace.NORMAL
    }
}

fun PkpdLearningPace.applyTo(preferences: Preferences) {
    when (this) {
        PkpdLearningPace.SLOW -> {
            preferences.put(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, 0.2)
            preferences.put(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, 2.0)
        }
        PkpdLearningPace.NORMAL -> {
            preferences.put(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, 0.5)
            preferences.put(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, 5.0)
        }
        PkpdLearningPace.FAST -> {
            preferences.put(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, 1.0)
            preferences.put(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, 10.0)
        }
    }
}

fun pkpdPrefsSnapshotFrom(preferences: Preferences): PkpdPrefsSnapshot =
    PkpdPrefsSnapshot(
        pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
        initialDiaH = preferences.get(DoubleKey.OApsAIMIPkpdInitialDiaH),
        initialPeakMin = preferences.get(DoubleKey.OApsAIMIPkpdInitialPeakMin),
        boundsDiaMinH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
        boundsDiaMaxH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
        boundsPeakMinMin = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
        boundsPeakMinMax = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
        maxDiaChangePerDayH = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH),
        maxPeakChangePerDayMin = preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin),
        isfFusionMinFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
        isfFusionMaxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
        isfFusionMaxChangePerTick = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick),
        smbTailThreshold = preferences.get(DoubleKey.OApsAIMISmbTailThreshold),
        smbTailDamping = preferences.get(DoubleKey.OApsAIMISmbTailDamping),
        smbExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping),
        smbLateFatDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping),
    )

fun applyPkpdPreferenceUpdate(preferences: Preferences, action: AimiAction.PreferenceUpdate): Boolean {
    return when (val value = action.newValue) {
        is Double -> (action.key as? DoublePreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        is Int -> (action.key as? IntPreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        is Boolean -> (action.key as? BooleanPreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        is String -> (action.key as? StringPreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        else -> false
    }
}

fun detectPkpdInsulinPreset(preferences: Preferences): PkpdInsulinPreset {
    fun near(a: Double, b: Double, eps: Double = 0.15) = abs(a - b) <= eps
    val bDiaLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
    val bDiaHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
    val bPeakLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)
    val bPeakHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)
    val anchorPeak = preferences.get(DoubleKey.OApsAIMIPkpdAnchorPeakMin)
    if (near(bDiaLo, 5.0) && near(bDiaHi, 8.0) && near(bPeakLo, 35.0) && near(anchorPeak, 55.0)) {
        return PkpdInsulinPreset.ULTRA_FAST
    }
    if (near(bDiaLo, 5.0) && near(bDiaHi, 11.0) && near(bPeakLo, 50.0) && near(anchorPeak, 75.0)) {
        return PkpdInsulinPreset.RAPID
    }
    if (near(bDiaLo, 6.0) && near(bDiaHi, 16.0) && near(bPeakLo, 65.0) && near(anchorPeak, 90.0)) {
        return PkpdInsulinPreset.STANDARD
    }
    return PkpdInsulinPreset.CUSTOM
}
