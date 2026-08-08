package app.aaps.plugins.aps.openAPSAIMI.ISF

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * Estimates **how strongly insulin actually acts for this patient**, as a dimensionless ratio on the
 * profile ISF.
 *
 * ## Why a ratio, and why from outcomes
 *
 * The commanded sensitivity is today a stack of ten stages — a BG staircase, a delta correction, two
 * disagreeing fast estimators combined by a maximum, a slow floor, three rate limiters and a cache —
 * and only 18 % of its movement is explained by the variables it is supposed to depend on. It is not
 * an estimate of anything; it is a dosing policy wearing the name of a physiological parameter.
 *
 * This estimator answers one question and nothing else: *given what was delivered, how much did
 * glucose actually fall?* It observes **closed windows** where the answer is readable — no bolus, a
 * decaying IOB, glucose going down — and folds each observation into a slow state.
 *
 * The result is a ratio, not an ISF, so it composes: `S = profileIsf × ratio`. The circadian shape
 * stays owned by the user's profile blocks, which is where it belongs.
 *
 * ## The basal deficit correction
 *
 * `iobU` is net of profile basal. When the loop runs a zero temp basal, IOB decays partly because
 * insulin acted and partly because insulin was **not given** — and the second part lowers nothing.
 * Ignoring it made the same production data read 9.7 mg/dL/U instead of 11.9. The deficit is
 * subtracted from the insulin credited with the fall.
 *
 * ## What it is not
 *
 * It cannot separate a meal from a drop in sensitivity within a single episode — measured on
 * 2026-08-04, a real meal and the fat tail of an earlier one were indistinguishable on BG, delta,
 * IOB and IOB slope. That is why observations are only taken in windows where nothing is being
 * digested, and why the state moves over hours, not minutes.
 *
 * See `docs/adr/0008-isf-decision-architecture.md`.
 */
@Singleton
class SensitivityRatioEstimator @Inject constructor() {

    data class Sample(
        val timestampMs: Long,
        val bgMgdl: Double,
        val iobU: Double,
        val profileBasalUph: Double,
        val deliveredBasalUph: Double,
        val smbU: Double,
        val profileIsfMgdl: Double,
    )

    /** One usable observation, kept for logging and tests. */
    data class Observation(
        val timestampMs: Long,
        val ratio: Double,
        val bgDropMgdl: Double,
        val insulinActedU: Double,
        val windowMinutes: Double,
    )

    private val samples = ArrayDeque<Sample>()

    /** Current slow state. Starts neutral: the profile is trusted until measurements say otherwise. */
    var ratio: Double = 1.0
        private set

    /** Last observation folded in, or `null` when none has been taken yet. */
    var lastObservation: Observation? = null
        private set

    /** Number of observations folded in since start, for confidence reporting. */
    var observationCount: Int = 0
        private set

    /**
     * Feeds one loop tick and returns the current ratio.
     *
     * Cheap and side-effect free apart from the internal state: safe to call on every tick,
     * including ticks where nothing else runs. An estimator that only updates when a controller
     * engages is not an estimator — that defect is why the meal model froze for hours at a time.
     */
    fun observe(sample: Sample): Double {
        if (!sample.isUsable()) return ratio
        samples.addLast(sample)
        while (samples.isNotEmpty() &&
            sample.timestampMs - samples.first().timestampMs > RETENTION_MINUTES * 60_000L
        ) {
            samples.removeFirst()
        }
        closedWindowObservation(sample)?.let { fold(it) }
        return ratio
    }

    /** Sensitivity this estimator would command for [profileIsfMgdl], bounded relative to it. */
    fun sensitivityMgdl(profileIsfMgdl: Double): Double =
        if (profileIsfMgdl.isFinite() && profileIsfMgdl > 0.0) {
            (profileIsfMgdl * ratio).coerceIn(profileIsfMgdl * MIN_RATIO, profileIsfMgdl * MAX_RATIO)
        } else {
            profileIsfMgdl
        }

    fun reset() {
        samples.clear()
        ratio = 1.0
        lastObservation = null
        observationCount = 0
    }

    private fun Sample.isUsable(): Boolean =
        timestampMs > 0L && bgMgdl.isFinite() && bgMgdl > 0.0 &&
            iobU.isFinite() && profileIsfMgdl.isFinite() && profileIsfMgdl > 0.0

    /**
     * Looks for a window ending at [end] where the fall can be attributed to insulin alone.
     *
     * Rejects anything with a bolus inside the window or in the run-up, a rise (which would mean
     * carbs are arriving), or too little insulin acting to measure against.
     */
    private fun closedWindowObservation(end: Sample): Observation? {
        val start = samples.firstOrNull { s ->
            val span = (end.timestampMs - s.timestampMs) / 60_000.0
            span in MIN_WINDOW_MINUTES..MAX_WINDOW_MINUTES
        } ?: return null

        val window = samples.filter { it.timestampMs in start.timestampMs..end.timestampMs }
        if (window.size < MIN_WINDOW_SAMPLES) return null

        val runUpStart = start.timestampMs - QUIET_RUN_UP_MINUTES * 60_000L
        val runUp = samples.filter { it.timestampMs in runUpStart until start.timestampMs }
        if (window.any { it.smbU > 0.0 } || runUp.any { it.smbU > 0.0 }) return null

        if (start.iobU < MIN_START_IOB_U) return null
        val drop = start.bgMgdl - end.bgMgdl
        if (drop <= 0.0) return null

        val acted = (start.iobU - end.iobU) - basalDeficitU(window)
        if (acted < MIN_ACTED_U) return null

        val modulation = window.map { bgModulation(it.bgMgdl) }.average()
        if (modulation <= 0.0) return null

        val observed = drop / acted / modulation / start.profileIsfMgdl
        if (!observed.isFinite() || observed <= 0.0) return null

        return Observation(
            timestampMs = end.timestampMs,
            ratio = observed,
            bgDropMgdl = drop,
            insulinActedU = acted,
            windowMinutes = (end.timestampMs - start.timestampMs) / 60_000.0,
        )
    }

    /** Insulin the loop did **not** give relative to the profile over the window, in units. */
    private fun basalDeficitU(window: List<Sample>): Double =
        window.zipWithNext().sumOf { (a, b) ->
            val hours = (b.timestampMs - a.timestampMs) / 3_600_000.0
            maxOf(0.0, a.profileBasalUph - a.deliveredBasalUph) * hours
        }

    private fun fold(observation: Observation) {
        val bounded = observation.ratio.coerceIn(MIN_OBSERVATION_RATIO, MAX_OBSERVATION_RATIO)
        val alpha = 1.0 - exp(-(TICK_MINUTES / 60.0) / TAU_HOURS)
        ratio = (ratio + alpha * (bounded - ratio)).coerceIn(MIN_RATIO, MAX_RATIO)
        lastObservation = observation
        observationCount++
    }

    companion object {

        /**
         * Situational modulation the observation is divided by, so the ratio measures sensitivity
         * and not the shape of the BG dependence. Normalised to 1 at [MODULATION_REFERENCE_MGDL].
         */
        fun bgModulation(bgMgdl: Double): Double {
            val safe = bgMgdl.coerceAtLeast(40.0)
            return ln(MODULATION_REFERENCE_MGDL / MODULATION_BASE_MGDL + 1) /
                ln(safe / MODULATION_BASE_MGDL + 1)
        }

        const val MODULATION_BASE_MGDL = 75.0
        const val MODULATION_REFERENCE_MGDL = 100.0

        /** Sensitivity moves over hours, not minutes. */
        const val TAU_HOURS = 12.0
        const val TICK_MINUTES = 5.0

        /** The commanded value stays inside the domain where the profile still means something. */
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 2.0

        /** A single window is never trusted beyond this, whatever it computes. */
        const val MIN_OBSERVATION_RATIO = 0.2
        const val MAX_OBSERVATION_RATIO = 3.0

        const val MIN_WINDOW_MINUTES = 36.0
        const val MAX_WINDOW_MINUTES = 63.0
        const val MIN_WINDOW_SAMPLES = 6
        const val QUIET_RUN_UP_MINUTES = 30L
        const val MIN_START_IOB_U = 0.8
        const val MIN_ACTED_U = 0.3
        const val RETENTION_MINUTES = 120L
    }
}
