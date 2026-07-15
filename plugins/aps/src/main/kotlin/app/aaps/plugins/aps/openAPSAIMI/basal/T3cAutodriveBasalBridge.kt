package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * T3C Basal Authority Fusion — pure decision helpers (no I/O).
 *
 * Contract:
 * - SMB is never applied; any Autodrive SMB is stripped (optionally converted to a bounded TBR boost).
 * - Autodrive may only **raise** basal demand vs the T3C PI (`max`).
 * - The physiological tree unlocks the **ceiling + ramp**, not a cosmetic aggressiveness bump alone.
 */
object T3cAutodriveBasalBridge {

    /** Spread stripped SMB over this horizon (hours) when converting to TBR-equivalent boost. */
    private const val SMB_TO_TBR_HORIZON_H = 0.25

    /** Hard cap on SMB→TBR conversion so a large model SMB cannot explode the basal. */
    private const val SMB_TO_TBR_BOOST_CAP_UPH = 3.0

    data class UnlockDecision(
        val unlock: Boolean,
        val reason: String,
    )

    data class FusionResult(
        val fusedTargetUph: Double,
        val maxBasalCapUph: Double,
        val maxStepUpUph: Double,
        val piUph: Double,
        val adEffectiveUph: Double?,
        val strippedSmbU: Double,
        val unlock: Boolean,
        val unlockReason: String,
    ) {
        fun toLogLine(): String =
            "T3C_AD_BASAL: pi=${"%.2f".format(Locale.US, piUph)} " +
                "ad=${adEffectiveUph?.let { "%.2f".format(Locale.US, it) } ?: "—"} " +
                "fused=${"%.2f".format(Locale.US, fusedTargetUph)} " +
                "unlock=$unlock ($unlockReason) " +
                "cap=${"%.2f".format(Locale.US, maxBasalCapUph)} " +
                "step=${"%.2f".format(Locale.US, maxStepUpUph)} " +
                "smbStripped=${"%.2f".format(Locale.US, strippedSmbU)}"
    }

    /**
     * Tree opens high-basal authority when rise + resistance/meal/hyper evidence and risk is not critical.
     * Activity / post-activity / hypo / post-hypo veto the unlock.
     */
    fun evaluateTreeUnlock(
        tree: PhysiologicalTreeSnapshot?,
        bg: Double,
        delta: Float,
        activationThreshold: Double,
        postHypoActive: Boolean,
        eventualBg: Double?,
        targetBg: Double,
    ): UnlockDecision {
        if (postHypoActive) return UnlockDecision(false, "post_hypo")
        if (tree == null) {
            // Fail-open for rise without tree: still allow unlock on hard glycemic evidence alone.
            val riseOnly = bg > activationThreshold + 15.0 &&
                delta >= 2.0f &&
                (eventualBg == null || eventualBg <= 0.0 || eventualBg > targetBg)
            return UnlockDecision(riseOnly, if (riseOnly) "rise_no_tree" else "no_tree")
        }
        val risk = tree.trunk.riskLevel
        if (risk == PhysiologicalRiskLevel.CRITICAL) return UnlockDecision(false, "tree_critical")
        if (tree.trunk.globalState == GlobalPhysiologicalState.HYPO_RISK) {
            return UnlockDecision(false, "tree_hypo_risk")
        }
        val activityConf = tree.branches.activity.confidence
        val postActivityConf = tree.branches.postActivity.confidence
        if (activityConf >= 0.55 || postActivityConf >= 0.45) {
            return UnlockDecision(false, "tree_activity")
        }
        val rising = bg > activationThreshold + 15.0 &&
            delta >= 1.5f &&
            (eventualBg == null || eventualBg <= 0.0 || eventualBg > targetBg)
        if (!rising) return UnlockDecision(false, "no_confirmed_rise")

        val b = tree.branches
        val resistanceLike =
            b.hormonalResistance.confidence >= 0.55 ||
                b.stress.confidence >= 0.55 ||
                b.insulinEffectiveness.confidence >= 0.55 ||
                b.hyperRisk.confidence >= 0.55 ||
                b.meal.confidence >= 0.55 ||
                tree.trunk.globalState == GlobalPhysiologicalState.RESISTANCE_PROBABLE ||
                tree.trunk.globalState == GlobalPhysiologicalState.MEAL_PROBABLE ||
                tree.trunk.globalState == GlobalPhysiologicalState.HYPER_RISK ||
                tree.trunk.globalState == GlobalPhysiologicalState.DIGESTION_ACTIVE ||
                risk == PhysiologicalRiskLevel.MODERATE ||
                risk == PhysiologicalRiskLevel.LOW

        return if (resistanceLike) {
            UnlockDecision(true, "tree_unlock_${tree.trunk.globalState.name.lowercase(Locale.US)}")
        } else {
            UnlockDecision(false, "tree_no_resistance_signal")
        }
    }

    /**
     * Strip Autodrive SMB and optionally convert it into a bounded TBR boost (basal-only contract).
     */
    fun effectiveAutodriveTbrUph(
        rawTbrUph: Double,
        strippedSmbU: Double,
        profileBasalUph: Double,
    ): Double {
        val base = rawTbrUph.coerceAtLeast(0.0)
        val boost = if (strippedSmbU > 0.0) {
            (strippedSmbU / SMB_TO_TBR_HORIZON_H).coerceIn(0.0, SMB_TO_TBR_BOOST_CAP_UPH)
        } else {
            0.0
        }
        // Prefer the stronger of (raw TBR) vs (profile + SMB-as-TBR); never below profile when AD asked for SMB.
        return if (boost > 0.0) {
            max(base, profileBasalUph + boost)
        } else {
            base
        }
    }

    fun fuse(
        piUph: Double,
        adTbrUph: Double?,
        strippedSmbU: Double,
        profileBasalUph: Double,
        steadyCapUph: Double,
        riseCapUph: Double,
        previousRateUph: Double,
        unlock: UnlockDecision,
    ): FusionResult {
        val adEffective = adTbrUph?.let {
            effectiveAutodriveTbrUph(it, strippedSmbU, profileBasalUph)
        }
        val demand = when {
            adEffective == null -> piUph
            else -> max(piUph, adEffective)
        }
        val cap = if (unlock.unlock) riseCapUph else steadyCapUph
        val fusedTarget = demand.coerceIn(0.0, cap.coerceAtLeast(profileBasalUph))
        val maxStepUp = if (unlock.unlock) {
            max(1.0, previousRateUph * 0.50)
        } else {
            max(0.30, previousRateUph * 0.20)
        }
        return FusionResult(
            fusedTargetUph = fusedTarget,
            maxBasalCapUph = cap,
            maxStepUpUph = maxStepUp,
            piUph = piUph,
            adEffectiveUph = adEffective,
            strippedSmbU = strippedSmbU.coerceAtLeast(0.0),
            unlock = unlock.unlock,
            unlockReason = unlock.reason,
        )
    }

    fun applyRamp(previousRateUph: Double, targetUph: Double, maxStepUpUph: Double): Double =
        if (targetUph > previousRateUph) {
            min(targetUph, previousRateUph + maxStepUpUph)
        } else {
            targetUph
        }
}
