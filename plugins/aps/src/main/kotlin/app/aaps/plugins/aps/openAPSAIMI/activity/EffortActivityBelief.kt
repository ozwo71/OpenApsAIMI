package app.aaps.plugins.aps.openAPSAIMI.activity

import kotlin.math.max
import kotlin.math.min

/**
 * Sensor-driven effort / activity belief.
 *
 * Fuses multi-window steps (5 / 15 / 60 min), heart-rate elevation, HRV depression and a stress
 * signal into a graded, **signed** posture ([Posture]) plus an effort-load **memory** that keeps the
 * protection alive after the activity ends (post-exercise insulin sensitivity lasts hours, whereas a
 * 15-min step window collapses in minutes).
 *
 * Key design points:
 *  - **Reduction-only output** ([Assessment.smbFactor] / [Assessment.basalFactor] are ≤ 1.0) — the
 *    insulin side can only be *lowered*; fail-safe direction.
 *  - **Exertion vs stress disambiguation**: HRV depression + HR elevation are common to both, but they
 *    pull insulin opposite ways (exercise → ↑sensitivity → less insulin; acute stress → ↑resistance).
 *    Steps disambiguate: movement ⇒ exertion (protect); HR↑ + HRV↓ + stress prob but no movement ⇒
 *    stress (do **not** reduce insulin).
 *  - **Pure & deterministic**: no clocks, no I/O. The caller owns [Memory] across ticks and passes
 *    `nowMs` in. This makes the whole branch unit-testable.
 */
object EffortActivityBelief {

    enum class State { IDLE, ACTIVE, RECENT_EFFORT }

    enum class Posture { NEUTRAL, EXERTION, STRESS }

    // ── Tunables (named for clarity and test anchoring) ─────────────────────────
    /** Steps/min that count as a brisk walk (full exertion intensity reference). */
    private const val WALK_STEPS_PER_MIN = 40.0
    /** Minimal sustained steps/min to consider the user "moving". */
    private const val ACTIVE_STEPS_PER_MIN = 25.0
    /** hrAvg15m − resting (bpm) that corroborates load. */
    private const val HR_ELEVATION_BPM = 25
    /** hrvDeviationZ at/under this is "depressed" (negative = below baseline). */
    private const val HRV_DEPRESSED_Z = -0.7
    /** How long recent-effort protection persists after movement stops (minutes). */
    private const val EFFORT_MEMORY_MIN = 120.0
    /** Strongest SMB reduction at full exertion (×0.45). */
    private const val SMB_FLOOR = 0.45
    /** Strongest basal reduction at full exertion (×0.70). */
    private const val BASAL_FLOOR = 0.70

    /** Cross-tick memory owned by the caller. */
    data class Memory(
        val lastEffortMs: Long = 0L,
        /** Peak steps/min seen during the current/last effort, scales the recent-effort strength. */
        val peakStepsPerMin: Double = 0.0,
    )

    data class Inputs(
        val nowMs: Long,
        val stepsLast5m: Int,
        val stepsLast15m: Int,
        val stepsLast60m: Int,
        val hrAvg15mBpm: Int,
        val hrRestingBpm: Int,
        /** Normalised HRV deviation (negative = depressed); null when HRV unavailable. */
        val hrvDeviationZ: Double?,
        /** Transient insulin-resistance / stress probability (0..1). */
        val stressResistanceProb: Double,
    )

    data class Assessment(
        val state: State,
        val posture: Posture,
        val confidence: Double,
        val minutesSinceEffort: Double,
        /** Multiplicative SMB cap, in [SMB_FLOOR, 1.0]. 1.0 = no reduction. */
        val smbFactor: Double,
        /** Multiplicative basal cap, in [BASAL_FLOOR, 1.0]. */
        val basalFactor: Double,
        val reasons: List<String>,
    ) {
        val reducesInsulin: Boolean get() = smbFactor < 1.0 || basalFactor < 1.0
    }

    fun assess(inputs: Inputs, prior: Memory): Pair<Assessment, Memory> {
        val reasons = mutableListOf<String>()

        // Step rate: most reactive window with data drives onset; 60-min window is the load.
        val rate5 = if (inputs.stepsLast5m > 0) inputs.stepsLast5m / 5.0 else 0.0
        val rate15 = if (inputs.stepsLast15m > 0) inputs.stepsLast15m / 15.0 else 0.0
        val stepRate = max(rate5, rate15)
        val sustainedRate = if (inputs.stepsLast60m > 0) inputs.stepsLast60m / 60.0 else 0.0

        val hrElevation = if (inputs.hrAvg15mBpm > 0 && inputs.hrRestingBpm > 0) {
            inputs.hrAvg15mBpm - inputs.hrRestingBpm
        } else {
            0
        }
        val hrvDepressed = inputs.hrvDeviationZ != null && inputs.hrvDeviationZ <= HRV_DEPRESSED_Z

        val exertingNow = stepRate >= ACTIVE_STEPS_PER_MIN
        if (exertingNow) reasons.add("steps=${stepRate.toInt()}/min(5=${rate5.toInt()},15=${rate15.toInt()})")
        if (hrElevation >= HR_ELEVATION_BPM) reasons.add("hr+$hrElevation")
        if (hrvDepressed) reasons.add("hrv_depressed")

        // Step-derived exertion intensity (0..1).
        val stepIntensity = when {
            stepRate >= WALK_STEPS_PER_MIN -> (0.6 + 0.4 * ((stepRate - WALK_STEPS_PER_MIN) / WALK_STEPS_PER_MIN)).coerceIn(0.6, 1.0)
            stepRate >= ACTIVE_STEPS_PER_MIN ->
                0.35 + 0.25 * ((stepRate - ACTIVE_STEPS_PER_MIN) / (WALK_STEPS_PER_MIN - ACTIVE_STEPS_PER_MIN))
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        val sustainedBonus = (sustainedRate / WALK_STEPS_PER_MIN).coerceIn(0.0, 0.30)
        val hrConf = (hrElevation.toDouble() / (HR_ELEVATION_BPM * 2)).coerceIn(0.0, 0.50)
        val hrvBonus = if (hrvDepressed) 0.15 else 0.0

        // Memory update: a fresh effort stamps the time and the peak rate. The peak is *not* carried
        // over from an already-expired prior effort, so a past hard effort cannot inflate the
        // protection of a later light one.
        val priorExpired = prior.lastEffortMs == 0L ||
            (inputs.nowMs - prior.lastEffortMs) / 60_000.0 > EFFORT_MEMORY_MIN
        val memory = if (exertingNow) {
            val basePeak = if (priorExpired) 0.0 else prior.peakStepsPerMin
            Memory(lastEffortMs = inputs.nowMs, peakStepsPerMin = max(basePeak, stepRate))
        } else {
            prior
        }
        val minutesSinceEffort = if (memory.lastEffortMs > 0L) {
            ((inputs.nowMs - memory.lastEffortMs) / 60_000.0).coerceAtLeast(0.0)
        } else {
            Double.MAX_VALUE
        }

        val state = when {
            exertingNow -> State.ACTIVE
            minutesSinceEffort <= EFFORT_MEMORY_MIN -> State.RECENT_EFFORT
            else -> State.IDLE
        }
        // Linear decay of recent-effort protection over the memory horizon.
        val recency = when (state) {
            State.ACTIVE -> 1.0
            State.RECENT_EFFORT -> (1.0 - minutesSinceEffort / EFFORT_MEMORY_MIN).coerceIn(0.0, 1.0)
            State.IDLE -> 0.0
        }

        // Posture: movement ⇒ exertion; HR↑ + (HRV↓ or stress) without movement ⇒ stress.
        val stress = inputs.stressResistanceProb.coerceIn(0.0, 1.0)
        val posture = when {
            exertingNow -> Posture.EXERTION
            state == State.RECENT_EFFORT -> Posture.EXERTION
            hrElevation >= HR_ELEVATION_BPM && (hrvDepressed || stress >= 0.55) -> Posture.STRESS
            else -> Posture.NEUTRAL
        }

        // Exertion strength drives the (reduction-only) insulin factors. Stress NEVER reduces insulin.
        val exertionStrength = when {
            state == State.ACTIVE ->
                (stepIntensity + sustainedBonus + hrConf + hrvBonus).coerceIn(0.0, 1.0)
            state == State.RECENT_EFFORT -> {
                val peakScale = (memory.peakStepsPerMin / WALK_STEPS_PER_MIN).coerceIn(0.30, 1.0)
                (recency * peakScale).coerceIn(0.0, 1.0)
            }
            else -> 0.0
        }

        val smbFactor = (1.0 - (1.0 - SMB_FLOOR) * exertionStrength).coerceIn(SMB_FLOOR, 1.0)
        val basalFactor = (1.0 - (1.0 - BASAL_FLOOR) * exertionStrength).coerceIn(BASAL_FLOOR, 1.0)

        val confidence = when (posture) {
            Posture.EXERTION -> max(exertionStrength, recency)
            Posture.STRESS -> (hrConf * 1.4 + hrvBonus + stress * 0.4).coerceIn(0.0, 1.0)
            Posture.NEUTRAL -> 0.0
        }

        if (posture == Posture.STRESS) reasons.add("stress=${"%.2f".format(stress)}")
        reasons.add("${state.name}/${posture.name} since=${if (minutesSinceEffort == Double.MAX_VALUE) "∞" else minutesSinceEffort.toInt().toString()}m")

        val assessment = Assessment(
            state = state,
            posture = posture,
            confidence = confidence,
            minutesSinceEffort = minutesSinceEffort,
            smbFactor = smbFactor,
            basalFactor = basalFactor,
            reasons = reasons,
        )
        return assessment to memory
    }
}
