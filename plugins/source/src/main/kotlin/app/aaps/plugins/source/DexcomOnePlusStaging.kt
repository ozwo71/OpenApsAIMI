package app.aaps.plugins.source

import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.interfaces.source.StagingState

/**
 * Pure helpers + tunable constants for the Dexcom ONE+ dual-sensor (pre-soak / staging) feature.
 * Kept out of [DexcomOnePlusPlugin] so the lifecycle / staging state machine is unit-testable without
 * the DI graph or Android. See docs/DEXCOM_ONEPLUS_DUAL_SENSOR_STAGING_PLAN.md (§3.3, §6).
 *
 * ⚠️ The time constants below are tunable and pending clinician review (plan §9).
 */
internal object DexcomOnePlusStaging {

    private const val HOUR_MS = 60L * 60L * 1000L

    /** Nominal ONE+/G7 sensor life. */
    const val SENSOR_LIFE_MS = 10L * 24L * HOUR_MS

    /** End-of-life grace extension. */
    const val SENSOR_GRACE_MS = 12L * HOUR_MS

    /** Early-life window: fresh sensor readings may be noisy ("jumpy"). */
    const val EARLY_LIFE_MS = 12L * HOUR_MS

    /** End-of-life window: prompt the user to start a new sensor. */
    const val END_OF_LIFE_MS = 12L * HOUR_MS

    /** Minimum staging duration before promotion is allowed. */
    const val STAGING_MIN_SETTLE_MS = 12L * HOUR_MS

    /** Minimum count of valid staging EGVs before promotion is allowed. */
    const val STAGING_MIN_VALID_EGV = 6

    /**
     * Derive a [CgmSensorLifecycle] from a sensor session start. Returns null when the start is
     * unknown (0) so the dashboard simply shows nothing special.
     */
    fun computeLifecycle(slot: SensorSlot, sessionStartMs: Long, nowMs: Long): CgmSensorLifecycle? {
        if (sessionStartMs <= 0L) return null
        val age = (nowMs - sessionStartMs).coerceAtLeast(0L)
        val expires = sessionStartMs + SENSOR_LIFE_MS + SENSOR_GRACE_MS
        val remaining = expires - nowMs
        return CgmSensorLifecycle(
            slot = slot,
            startedAtEpochMs = sessionStartMs,
            expiresAtEpochMs = expires,
            ageMs = age,
            remainingMs = remaining,
            earlyLife = age < EARLY_LIFE_MS,
            endOfLife = remaining < END_OF_LIFE_MS,
        )
    }

    /**
     * Coarse staging slot state for the dashboard card + promote gating.
     *
     * @param present     a staging sensor session exists.
     * @param warming     the staging sensor is warming up / (re)connecting (no glucose yet).
     * @param sessionStartMs staging session start (0 = unknown).
     * @param validEgvCount valid staging EGVs collected so far.
     */
    fun computeStagingState(
        present: Boolean,
        warming: Boolean,
        sessionStartMs: Long,
        validEgvCount: Int,
        nowMs: Long,
    ): StagingState {
        if (!present) return StagingState.ABSENT
        if (warming) return StagingState.WARMUP
        val settledLongEnough = sessionStartMs > 0L && (nowMs - sessionStartMs) >= STAGING_MIN_SETTLE_MS
        return if (settledLongEnough && validEgvCount >= STAGING_MIN_VALID_EGV) StagingState.READY
        else StagingState.SETTLING
    }
}
