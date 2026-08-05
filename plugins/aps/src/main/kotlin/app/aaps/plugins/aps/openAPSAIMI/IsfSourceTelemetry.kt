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
     * User profile ISF block for the current time of day, captured where the profile is in hand.
     * `Profile.getProfileIsfMgdl()` is not suspend, but `ProfileFunction.getProfile()` is, so the
     * dosing path cannot read it directly.
     */
    @Volatile
    var lastProfileStaticMgdl: Double? = null
        private set

    fun record(source: String, dynamicMgdl: Double?, ageMs: Long?) {
        lastSource = source
        lastDynamicMgdl = dynamicMgdl
        lastAgeMs = ageMs
    }

    fun recordProfileStatic(profileStaticMgdl: Double?) {
        lastProfileStaticMgdl = profileStaticMgdl
    }

    fun reset() {
        lastSource = SOURCE_NONE
        lastDynamicMgdl = null
        lastAgeMs = null
        lastProfileStaticMgdl = null
    }
}
