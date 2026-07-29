package app.aaps.core.interfaces.source

import kotlinx.coroutines.flow.StateFlow

/**
 * Which logical sensor a reading / status belongs to when a CGM source supports overlapping a new
 * sensor over the outgoing one (pre-soak / staging).
 *
 * [PRODUCTION] is the sensor that feeds the loop (published glucose). [STAGING] is a second sensor
 * being warmed up / stabilised in parallel that is **never** published to the loop until the user
 * explicitly promotes it. See docs/DEXCOM_ONEPLUS_DUAL_SENSOR_STAGING_PLAN.md.
 */
enum class SensorSlot { PRODUCTION, STAGING }

/**
 * State of the optional STAGING slot, for the dashboard's "new sensor" card.
 *
 * - [ABSENT]   no staging sensor started.
 * - [WARMUP]   staging sensor warming up / (re)connecting (no glucose yet).
 * - [SETTLING] warmed up, collecting glucose but not yet settled long enough to promote.
 * - [READY]    settled ≥ minimum AND producing valid glucose → promotion allowed.
 */
enum class StagingState { ABSENT, WARMUP, SETTLING, READY }

/**
 * Generic, source-agnostic sensor lifecycle info so the dashboard can surface a sensor's
 * "beginning of life" (early, potentially jumpy readings) and end of life (time to start a new one)
 * — without hard-coding any vendor plugin.
 *
 * All times are epoch ms. Fields are null when the source cannot determine them.
 *
 * @param slot which sensor this describes.
 * @param startedAtEpochMs sensor session start.
 * @param expiresAtEpochMs nominal expiry (start + nominal life + grace).
 * @param ageMs now − startedAt.
 * @param remainingMs expiresAt − now.
 * @param earlyLife true while the sensor is in its early-life window (readings may be noisy).
 * @param endOfLife true while the sensor is within its end-of-life window (prompt to start a new one).
 */
data class CgmSensorLifecycle(
    val slot: SensorSlot,
    val startedAtEpochMs: Long?,
    val expiresAtEpochMs: Long?,
    val ageMs: Long?,
    val remainingMs: Long?,
    val earlyLife: Boolean,
    val endOfLife: Boolean,
)

/** Why a promote-staging-to-production request was rejected (for UI feedback + audit). */
enum class PromotionRejectReason { STAGING_ABSENT, STAGING_NOT_SETTLED, STAGING_NO_VALID_GLUCOSE, LOOP_BUSY }

/** Result of a promote-staging-to-production request. */
sealed interface PromotionResult {
    /** Promotion succeeded — the staging sensor now feeds the loop. */
    data object Ok : PromotionResult

    /** Promotion refused; [reason] says why (no state changed). */
    data class Rejected(val reason: PromotionRejectReason) : PromotionResult
}

/**
 * Implemented by a [BgSource] plugin that can overlap a second (staging) sensor over the production
 * one. Extends [CgmWarmupProvider] (the existing production warm-up surface stays source of truth for
 * the hero ring) with lifecycle + staging status the dashboard reads generically:
 * `(activeBgSource as? CgmSensorStatusProvider)`.
 *
 * The STAGING slot is collect-only — it is never published to the loop until [promoteStagingToProduction].
 */
interface CgmSensorStatusProvider : CgmWarmupProvider {

    /** Production sensor lifecycle (early/end of life), or null when unknown / no sensor. */
    val lifecycle: StateFlow<CgmSensorLifecycle?>

    /** Staging warm-up status, or null when there is no staging sensor / nothing to show. */
    val stagingWarmupStatus: StateFlow<CgmWarmupStatus?>

    /** Staging sensor lifecycle, or null when there is no staging sensor. */
    val stagingLifecycle: StateFlow<CgmSensorLifecycle?>

    /** Coarse staging slot state driving the dashboard "new sensor" card + promote affordance. */
    val stagingState: StateFlow<StagingState>

    /**
     * Promote the staging sensor to production (the ONLY action that changes the loop's glucose
     * source — safety-critical). Suspends; returns [PromotionResult.Ok] on success or
     * [PromotionResult.Rejected] with the reason (e.g. not settled) without changing any state.
     */
    suspend fun promoteStagingToProduction(): PromotionResult
}
