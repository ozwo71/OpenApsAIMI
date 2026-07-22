package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Distinct IOB / COB / UAM / ZT prediction paths for graphing and safety composite (Phase 4A).
 * [hybrid] matches legacy [AdvancedPredictionEngine.predict] — insulin + COB + UAM momentum combined.
 *
 * Wave4 H3: when endogenous reversion is enabled, insulin-only series share EGP with hybrid.
 * [insulinPathMinRawMgdl] / [insulinPathMinSoftMgdl] support JSON study (pre-EGP vs published).
 * Field correction 2026-07-22: Guard A caps the EGP anchor at current BG; Guard B suspends EGP on
 * hard falls ([endogenousReversionSuppressedByTrend]).
 */
data class AdvancedPredictionCurves(
    val iob: List<Double>,
    val cob: List<Double>,
    val uam: List<Double>,
    val zt: List<Double>,
    val hybrid: List<Double>,
    /** Min of insulin-only points before EGP lift (null if not tracked). */
    val insulinPathMinRawMgdl: Double? = null,
    /** Min of published insulin-only points after EGP (null if not tracked). */
    val insulinPathMinSoftMgdl: Double? = null,
    /** True when EGP actually raised at least one insulin-only step. */
    val endogenousReversionOnInsulinCurves: Boolean = false,
    /** Guard B — true when EGP was suspended for this tick because BG was falling hard. */
    val endogenousReversionSuppressedByTrend: Boolean = false,
) {
    val uamTerminal: Double? get() = uam.lastOrNull()?.takeIf { it.isFinite() }
    val cobTerminal: Double? get() = cob.lastOrNull()?.takeIf { it.isFinite() }
    val hybridTerminal: Double? get() = hybrid.lastOrNull()?.takeIf { it.isFinite() }
}
