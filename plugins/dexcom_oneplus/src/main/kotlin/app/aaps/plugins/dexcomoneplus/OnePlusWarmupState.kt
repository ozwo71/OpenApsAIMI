package app.aaps.plugins.dexcomoneplus

/**
 * Warm-up clock exposed to UI (A8). Prefer protocol remainingMs when available.
 *
 * Helpers for remaining resolution live in `warmup.OnePlusWarmupClock` (pure, unit-tested).
 */
data class OnePlusWarmupState(
    val phase: Phase,
    val remainingMs: Long? = null,
    val endsAtEpochMs: Long? = null,
    val message: String? = null,
) {
    enum class Phase {
        IDLE,
        PAIRING,
        WARMING,
        READY,
        FAILED,
    }
}
