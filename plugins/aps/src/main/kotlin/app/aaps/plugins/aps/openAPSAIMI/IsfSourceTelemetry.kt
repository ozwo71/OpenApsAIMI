package app.aaps.plugins.aps.openAPSAIMI

/**
 * Diagnostic-only side channel that records **which sensitivity source** the last
 * `OpenAPSAIMIPlugin.getIsfMgdl` call actually returned.
 *
 * ## Why this exists
 *
 * `getIsfMgdl` returns a bare `Double?`, and a `null` makes `ProfileSealed.getIsfMgdl` fall back to
 * the static profile ISF — a different quantity, chosen silently. The cached branch can also return
 * a value computed for an earlier tick, because the recomputation runs on a background scope. From
 * a support package it was impossible to tell which of the three happened, so a value moving by a
 * factor of ten between two consecutive ticks could not be explained.
 *
 * This object makes the choice observable. It is written on every call and read once per tick when
 * the decision snapshot is assembled. It never feeds a dose calculation.
 *
 * See `docs/adr/0003-dynisf-cache-read-path.md`.
 */
object IsfSourceTelemetry {

    /** No call recorded yet for this process. */
    const val SOURCE_NONE = "NONE"

    /** Cache hit whose entry was written for the current tick. */
    const val SOURCE_DYNAMIC_FRESH = "DYNAMIC_FRESH"

    /** Cache hit whose entry is older than one CGM interval. */
    const val SOURCE_DYNAMIC_STALE = "DYNAMIC_STALE"

    /** No usable cache entry: the caller falls back to the static profile ISF. */
    const val SOURCE_PROFILE_FALLBACK = "PROFILE_FALLBACK"

    /** Profile is not an effective profile switch, so no dynamic value can be produced. */
    const val SOURCE_NO_EPS = "NO_EPS"

    /** Age (ms) above which a cached dynamic value is reported as stale. */
    const val STALE_AFTER_MS: Long = 5 * 60 * 1000L

    @Volatile
    var lastSource: String = SOURCE_NONE
        private set

    /** Dynamic value before the effective-profile multiplier, or `null` when none was available. */
    @Volatile
    var lastDynamicMgdl: Double? = null
        private set

    /** Age (ms) of the cache entry that was used, or `null` when no cache entry was used. */
    @Volatile
    var lastAgeMs: Long? = null
        private set

    /**
     * Key of the cache entry that was used.
     *
     * [lastAgeMs] is derived from the key's 30-minute bucket, so it cannot tell two entries of the
     * same bucket apart — and the read rule selects **within** a bucket by glucose. Production data
     * showed 4 sensitivity jumps out of 20 where the age kept rising while the value changed, which
     * is exactly that case and was unreadable without the key.
     */
    @Volatile
    var lastCacheKey: Long? = null
        private set

    /** Glucose embedded in the cache key, i.e. the reading the used value was computed for. */
    @Volatile
    var lastCacheGlucoseMgdl: Long? = null
        private set

    /**
     * User profile ISF block for the current time of day, captured where the profile is in hand.
     * `Profile.getProfileIsfMgdl()` is not suspend, but `ProfileFunction.getProfile()` is, so the
     * dosing path cannot read it directly.
     */
    @Volatile
    var lastProfileStaticMgdl: Double? = null
        private set

    fun record(
        source: String,
        dynamicMgdl: Double?,
        ageMs: Long?,
        cacheKey: Long? = null,
        cacheGlucoseMgdl: Long? = null,
    ) {
        lastSource = source
        lastDynamicMgdl = dynamicMgdl
        lastAgeMs = ageMs
        lastCacheKey = cacheKey
        lastCacheGlucoseMgdl = cacheGlucoseMgdl
    }

    fun recordProfileStatic(profileStaticMgdl: Double?) {
        lastProfileStaticMgdl = profileStaticMgdl
    }

    /**
     * Intermediate terms of one `calculateVariableIsf` pass.
     *
     * Production data showed that the BG-dependent terms of the formula explain only 18 % of the
     * variation of the sensitivity actually commanded (R² = 0.18 over 285 ticks), and that no other
     * intended input explains much either. More than half of the movement comes from the estimation
     * chain itself, and it cannot be attributed without seeing the terms separately.
     */
    @Volatile var lastKalmanFastIsf: Double? = null; private set
    @Volatile var lastIsfAdjEngine: Double? = null; private set
    @Volatile var lastFusedSlowIsf: Double? = null; private set
    @Volatile var lastTrustFast: Double? = null; private set
    @Volatile var lastDynamicFactor: Double? = null; private set
    @Volatile var lastTrajectoryMultiplier: Double? = null; private set

    fun recordComponents(
        kalmanFastIsf: Double?,
        isfAdjEngine: Double?,
        fusedSlowIsf: Double?,
        trustFast: Double?,
        dynamicFactor: Double?,
        trajectoryMultiplier: Double?,
    ) {
        lastKalmanFastIsf = kalmanFastIsf
        lastIsfAdjEngine = isfAdjEngine
        lastFusedSlowIsf = fusedSlowIsf
        lastTrustFast = trustFast
        lastDynamicFactor = dynamicFactor
        lastTrajectoryMultiplier = trajectoryMultiplier
    }

    fun reset() {
        lastSource = SOURCE_NONE
        lastDynamicMgdl = null
        lastAgeMs = null
        lastCacheKey = null
        lastCacheGlucoseMgdl = null
        lastProfileStaticMgdl = null
    }
}
