package app.aaps.plugins.aps.openAPSAIMI.pkpd

/** Shared tail-damping scale for loop math and the simplified PK/PD settings UI. */
object PkpdSmbTailDamping {
    const val DAMPING_CAUTIOUS = 0.92
    const val DAMPING_NEUTRAL = 0.85
    const val DAMPING_PERMISSIVE = 0.70

    /** Pre-simplified UI stored tail damping on 0.0–1.0 scale; values below this are legacy. */
    const val LEGACY_NEUTRAL_CUTOFF = 0.55

    fun effectiveStoredValue(stored: Double): Double =
        if (stored <= LEGACY_NEUTRAL_CUTOFF) DAMPING_NEUTRAL else stored

    fun dampingForSliderLevel(level: Double): Double {
        val t = level.coerceIn(0.0, 1.0)
        return when {
            t <= 0.5 -> lerp(DAMPING_CAUTIOUS, DAMPING_NEUTRAL, t * 2.0)
            else -> lerp(DAMPING_NEUTRAL, DAMPING_PERMISSIVE, (t - 0.5) * 2.0)
        }
    }

    fun sliderLevelFromDamping(damping: Double): Double {
        if (damping <= LEGACY_NEUTRAL_CUTOFF) return 0.5
        return when {
            damping >= DAMPING_CAUTIOUS - 0.02 -> 0.0
            damping <= DAMPING_PERMISSIVE + 0.02 -> 1.0
            damping >= DAMPING_NEUTRAL ->
                0.5 * (DAMPING_CAUTIOUS - damping) / (DAMPING_CAUTIOUS - DAMPING_NEUTRAL)
            else ->
                0.5 + 0.5 * (DAMPING_NEUTRAL - damping) / (DAMPING_NEUTRAL - DAMPING_PERMISSIVE)
        }.coerceIn(0.0, 1.0)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}
