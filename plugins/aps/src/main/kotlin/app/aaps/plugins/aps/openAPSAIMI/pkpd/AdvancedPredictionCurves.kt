package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Distinct IOB / COB / UAM / ZT prediction paths for graphing and safety composite (Phase 4A).
 * [hybrid] matches legacy [AdvancedPredictionEngine.predict] — insulin + COB + UAM momentum combined.
 */
data class AdvancedPredictionCurves(
    val iob: List<Double>,
    val cob: List<Double>,
    val uam: List<Double>,
    val zt: List<Double>,
    val hybrid: List<Double>,
) {
    val uamTerminal: Double? get() = uam.lastOrNull()?.takeIf { it.isFinite() }
    val cobTerminal: Double? get() = cob.lastOrNull()?.takeIf { it.isFinite() }
    val hybridTerminal: Double? get() = hybrid.lastOrNull()?.takeIf { it.isFinite() }
}
