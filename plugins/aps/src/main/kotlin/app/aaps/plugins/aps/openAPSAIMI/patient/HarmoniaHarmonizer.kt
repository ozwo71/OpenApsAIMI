package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlin.math.abs

/**
 * Second-pass harmonization on proposed delivery (CONFIRM / SOFTEN / BLOCK) from tree + simulation.
 */
object HarmoniaHarmonizer {

    enum class Posture {
        CONFIRM,
        SOFTEN,
        BLOCK,
    }

    data class Outcome(
        val posture: Posture,
        val tbrFactor: Double = 1.0,
        val smbFactor: Double = 1.0,
        val reasons: List<String> = emptyList(),
    ) {
        fun toJsonSummary(): String =
            "${posture.name.lowercase()} tbr×${"%.2f".format(tbrFactor)} smb×${"%.2f".format(smbFactor)}"
    }

    private const val GENUINE_HYPO_BG_MAX_MGDL = 140.0
    private const val GENUINE_HYPO_LOOKBACK_MAX_MGDL = 85.0
    private const val HYPER_CORRECTION_BG_MIN_MGDL = 180.0

    fun evaluate(
        tree: PhysiologicalTreeSnapshot?,
        simulation: HarmoniaDecision?,
        bgMgdl: Double,
        deltaMgdl5m: Double,
        profileBasalUph: Double,
        proposedTbrUph: Double?,
        eventualBgMgdl: Double?,
        targetBgMgdl: Double,
        correctionFragilityScore: Double,
        postHyperExhaustionScore: Double,
        minBgLookback75m: Double? = null,
    ): Outcome? {
        val hasFragilityPressure =
            correctionFragilityScore >= 0.68 || postHyperExhaustionScore >= 0.72
        if (tree == null && simulation == null && !hasFragilityPressure) return null
        val reasons = mutableListOf<String>()

        if (tree?.trunk?.riskLevel == PhysiologicalRiskLevel.CRITICAL) {
            val hyperCorrectionDominant =
                bgMgdl >= HYPER_CORRECTION_BG_MIN_MGDL &&
                deltaMgdl5m > 0.0 &&
                tree.branches.hyperRisk.confidence >= 0.55
            if (!hyperCorrectionDominant) {
                return Outcome(Posture.BLOCK, tbrFactor = 0.0, smbFactor = 0.0, reasons = listOf("critical_physio_risk"))
            }
        }

        if (simulation != null && !simulation.eligible) {
            val hypoBlock = simulation.blockers.any { it.contains("hypo") || it.contains("low_or_falling") }
            val genuineHypoContext = isGenuineHypoContext(bgMgdl, minBgLookback75m)
            if (hypoBlock && genuineHypoContext) {
                return Outcome(Posture.SOFTEN, tbrFactor = 0.85, smbFactor = 0.5, reasons = listOf("harmonia_hypo_block"))
            }
        }

        if (correctionFragilityScore >= 0.68 || postHyperExhaustionScore >= 0.72) {
            reasons += "fragility=${"%.2f".format(correctionFragilityScore)}"
            return Outcome(
                posture = Posture.SOFTEN,
                tbrFactor = 0.82,
                smbFactor = 0.55,
                reasons = reasons + "post_hyper_fragility",
            )
        }

        val tbr = proposedTbrUph
        if (tbr != null && profileBasalUph > 0.0) {
            val stable = bgMgdl in 95.0..140.0 && abs(deltaMgdl5m) < 1.5
            val overshoot = eventualBgMgdl?.let { it > targetBgMgdl + 80.0 } == true
            if (stable && tbr > profileBasalUph * 1.45 && overshoot) {
                reasons += "stable_bg_high_tbr_eventual"
                return Outcome(Posture.SOFTEN, tbrFactor = 0.88, smbFactor = 0.7, reasons = reasons)
            }
        }

        val mealLeaf = tree?.branches?.meal
        if (
            mealLeaf != null &&
            mealLeaf.confidence >= 0.60 &&
            deltaMgdl5m >= 1.0 &&
            simulation?.action == HarmoniaAction.MEAL_SUPPORT
        ) {
            reasons += "tree_meal_support"
            return Outcome(Posture.CONFIRM, tbrFactor = 1.0, smbFactor = 1.0, reasons = reasons)
        }

        if (simulation?.action == HarmoniaAction.PROTECTIVE_REDUCTION ||
            simulation?.action == HarmoniaAction.STABILIZE
        ) {
            reasons += "harmonia_${simulation.action.name.lowercase()}"
            return Outcome(Posture.SOFTEN, tbrFactor = 0.90, smbFactor = 0.65, reasons = reasons)
        }

        return Outcome(Posture.CONFIRM, reasons = listOf("harmonia_no_objection"))
    }

    internal fun isGenuineHypoContext(bgMgdl: Double, minBgLookback75m: Double?): Boolean =
        bgMgdl < GENUINE_HYPO_BG_MAX_MGDL ||
        (minBgLookback75m?.let { it < GENUINE_HYPO_LOOKBACK_MAX_MGDL } == true)
}
