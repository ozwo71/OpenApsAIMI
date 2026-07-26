package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single meal-language for the decision cascade (Tree → Harmonia → Auditor).
 * See [docs/AIMI_DECISION_CASCADE_CONTRACT.md] §6.
 */
enum class MealCertaintyLevel {
    NONE,
    LOW,
    MED,
    HIGH,
}

enum class MealCertaintyTreeState {
    NONE,
    MEAL_PROBABLE,
    DIGESTION_ACTIVE,
}

enum class MealRiseGeometry {
    FALLING,
    WEAK,
    OK,
}

enum class MealTerminalsAgree {
    OK,
    PKPD_FLOOR_CONFLICT,
    HYPO_CONFLICT,
    UNKNOWN,
}

data class MealCertainty(
    val level: MealCertaintyLevel,
    val treeState: MealCertaintyTreeState,
    val absorptionPhase: MealAbsorptionPhase,
    val riseGeometry: MealRiseGeometry,
    val terminalsAgree: MealTerminalsAgree,
    val effortVeto: Boolean,
    val softCorroboration: Boolean,
    val reasons: List<String> = emptyList(),
) {
    /** H4 bridge: digestion + strong rise → meal support beats protective. */
    val supportsMealOverProtective: Boolean
        get() = level == MealCertaintyLevel.HIGH

    /** Productive meal support without beating activity protective. */
    val supportsMealSupport: Boolean
        get() = level == MealCertaintyLevel.HIGH || level == MealCertaintyLevel.MED

    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("level", level.name)
            put("tree_state", treeState.name)
            put("absorption_phase", absorptionPhase.name)
            put("rise_geometry", riseGeometry.name)
            put("terminals_agree", terminalsAgree.name)
            put("effort_veto", effortVeto)
            put("soft_corroboration", softCorroboration)
            put("supports_meal_over_protective", supportsMealOverProtective)
            put("supports_meal_support", supportsMealSupport)
            put("reasons", JSONArray(reasons))
            put("source", "meal_certainty_v1")
        }

    companion object {
        val NONE: MealCertainty =
            MealCertainty(
                level = MealCertaintyLevel.NONE,
                treeState = MealCertaintyTreeState.NONE,
                absorptionPhase = MealAbsorptionPhase.NONE,
                riseGeometry = MealRiseGeometry.WEAK,
                terminalsAgree = MealTerminalsAgree.UNKNOWN,
                effortVeto = false,
                softCorroboration = false,
                reasons = listOf("absent"),
            )
    }
}

object MealCertaintyBuilder {

    /** Deep-hyper floor (mg/dL) for effort-veto override on a strong digestion rise. */
    internal const val EFFORT_VETO_OVERRIDE_MIN_BG_MGDL = 200.0

    /** Strong 5‑min rise (mg/dL) required with an active absorption wave to override effort_veto. */
    internal const val EFFORT_VETO_OVERRIDE_MIN_DELTA_MGDL5M = 4.0

    data class Input(
        val trunkState: GlobalPhysiologicalState?,
        val mealBranchConfidence: Double = 0.0,
        val digestionDetected: Boolean = false,
        val absorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        val bgMgdl: Double,
        val deltaMgdl5m: Double,
        val targetBgMgdl: Double?,
        val cobG: Double = 0.0,
        val mealRiseConfirmedLegacy: Boolean = false,
        val effortVeto: Boolean = false,
        val softCorroboration: Boolean = false,
        val pkpdEventualMgdl: Double? = null,
        val scenarioTerminalMgdl: Double? = null,
        val scenarioPathMinMgdl: Double? = null,
        val scenarioPathMinHitFloor: Boolean = false,
    )

    fun evaluate(input: Input): MealCertainty {
        val reasons = mutableListOf<String>()
        val treeState = treeStateOf(input.trunkState)
        val rise = riseGeometryOf(input.deltaMgdl5m)
        val terminals = terminalsAgreeOf(input)
        val phase = input.absorptionPhase

        if (input.effortVeto) reasons.add("effort_veto")
        if (input.softCorroboration) reasons.add("soft_hr_corroboration")
        when (rise) {
            MealRiseGeometry.FALLING -> reasons.add("rise_falling")
            MealRiseGeometry.WEAK -> reasons.add("rise_weak")
            MealRiseGeometry.OK -> reasons.add("rise_ok")
        }
        when (terminals) {
            MealTerminalsAgree.HYPO_CONFLICT -> reasons.add("terminals_hypo_conflict")
            MealTerminalsAgree.PKPD_FLOOR_CONFLICT -> reasons.add("terminals_pkpd_floor_conflict")
            MealTerminalsAgree.OK -> reasons.add("terminals_ok")
            MealTerminalsAgree.UNKNOWN -> reasons.add("terminals_unknown")
        }

        val level = resolveLevel(
            input = input,
            treeState = treeState,
            rise = rise,
            terminals = terminals,
            phase = phase,
            reasons = reasons,
        )

        return MealCertainty(
            level = level,
            treeState = treeState,
            absorptionPhase = phase,
            riseGeometry = rise,
            terminalsAgree = terminals,
            effortVeto = input.effortVeto,
            softCorroboration = input.softCorroboration,
            reasons = reasons.distinct(),
        )
    }

    fun fromTreeAndEnvironment(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
        absorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        effortVeto: Boolean = false,
        softCorroboration: Boolean = false,
        pkpdEventualMgdl: Double? = null,
        scenarioTerminalMgdl: Double? = null,
        scenarioPathMinMgdl: Double? = null,
        scenarioPathMinHitFloor: Boolean = false,
    ): MealCertainty =
        evaluate(
            Input(
                trunkState = tree.trunk.globalState,
                mealBranchConfidence = tree.branches.meal.confidence,
                digestionDetected = tree.branches.digestion.detected,
                absorptionPhase = absorptionPhase,
                bgMgdl = env.currentBgMgdl,
                deltaMgdl5m = env.deltaMgdl5m,
                targetBgMgdl = env.targetBgMgdl,
                cobG = env.cobG,
                mealRiseConfirmedLegacy = env.mealRiseConfirmed,
                effortVeto = effortVeto,
                softCorroboration = softCorroboration,
                pkpdEventualMgdl = pkpdEventualMgdl,
                scenarioTerminalMgdl = scenarioTerminalMgdl,
                scenarioPathMinMgdl = scenarioPathMinMgdl,
                scenarioPathMinHitFloor = scenarioPathMinHitFloor,
            ),
        )

    internal fun softCorroborationFromPhysio(physioLive: PhysioLiveDigest?): Boolean {
        if (physioLive == null) return false
        val hr = physioLive.hrNowBpm
        val rhr = physioLive.rhrRestingBpm
        if (hr <= 0 || rhr <= 0) return false
        val idle = physioLive.activityState.equals("IDLE", ignoreCase = true) ||
            physioLive.activityState.equals("STILL", ignoreCase = true)
        val stepsLow = physioLive.stepsLast15m < 80
        // Mild elevation vs RHR while idle — never a lead signal alone.
        return idle && stepsLow && hr >= rhr + 12
    }

    private fun treeStateOf(trunk: GlobalPhysiologicalState?): MealCertaintyTreeState =
        when (trunk) {
            GlobalPhysiologicalState.DIGESTION_ACTIVE -> MealCertaintyTreeState.DIGESTION_ACTIVE
            GlobalPhysiologicalState.MEAL_PROBABLE -> MealCertaintyTreeState.MEAL_PROBABLE
            else -> MealCertaintyTreeState.NONE
        }

    private fun riseGeometryOf(deltaMgdl5m: Double): MealRiseGeometry =
        when {
            !deltaMgdl5m.isFinite() -> MealRiseGeometry.WEAK
            deltaMgdl5m < 0.0 -> MealRiseGeometry.FALLING
            deltaMgdl5m >= HarmoniaDecisionEngine.H4_MIN_RISING_DELTA_MGDL -> MealRiseGeometry.OK
            else -> MealRiseGeometry.WEAK
        }

    private fun terminalsAgreeOf(input: Input): MealTerminalsAgree {
        val pathMin = input.scenarioPathMinMgdl
        if (pathMin != null && pathMin.isFinite()) {
            if (input.scenarioPathMinHitFloor || pathMin < ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL) {
                return MealTerminalsAgree.HYPO_CONFLICT
            }
        }
        val pkpd = input.pkpdEventualMgdl
        val scn = input.scenarioTerminalMgdl
        if (
            pkpd != null && scn != null && pkpd.isFinite() && scn.isFinite() &&
            pkpd < 50.0 && scn - pkpd >= ClampPkpdScenarioReconcile.MIN_DIVERGENCE_MGDL
        ) {
            return MealTerminalsAgree.PKPD_FLOOR_CONFLICT
        }
        if (pkpd == null && scn == null && pathMin == null) {
            return MealTerminalsAgree.UNKNOWN
        }
        return MealTerminalsAgree.OK
    }

    private fun resolveLevel(
        input: Input,
        treeState: MealCertaintyTreeState,
        rise: MealRiseGeometry,
        terminals: MealTerminalsAgree,
        phase: MealAbsorptionPhase,
        reasons: MutableList<String>,
    ): MealCertaintyLevel {
        if (terminals == MealTerminalsAgree.HYPO_CONFLICT) {
            reasons.add("level_none_hypo_terminals")
            return MealCertaintyLevel.NONE
        }
        // Effort veto blocks undeclared HIGH/MED; declared COB may keep MED.
        val effortBlocksMeal = input.effortVeto && input.cobG < 3.0
        if (effortBlocksMeal && treeState == MealCertaintyTreeState.NONE && !phase.isActive) {
            reasons.add("level_none_effort")
            return MealCertaintyLevel.NONE
        }

        val target = input.targetBgMgdl
        val aboveMealBand =
            target != null &&
                target.isFinite() &&
                input.bgMgdl.isFinite() &&
                input.bgMgdl > target + PostHypoAggressiveRiseExit.TARGET_MARGIN_MGDL

        val digestionRiseCore =
            treeState == MealCertaintyTreeState.DIGESTION_ACTIVE &&
                rise == MealRiseGeometry.OK &&
                aboveMealBand &&
                terminals != MealTerminalsAgree.HYPO_CONFLICT

        // Undeclared-meal hyper plateaus (e.g. 25/07 14:42): postprandial HR/steps often trip
        // effort_veto while the trunk is already DIGESTION_ACTIVE + FIRST_WAVE with a strong rise.
        // That pinned MealCertainty to LOW → Harmonia PROTECTIVE_REDUCTION (smb_factor=0) despite
        // MEAL_UNDECLARED_FAST. Override only on deep hyper + strong Δ + active absorption wave;
        // milder effort+meal cases stay LOW (see effortVeto_blocksHighAndMedWhenUndeclared).
        val effortVetoOverriddenByStrongMealRise =
            effortBlocksMeal &&
                digestionRiseCore &&
                phase.isActive &&
                input.bgMgdl >= EFFORT_VETO_OVERRIDE_MIN_BG_MGDL &&
                input.deltaMgdl5m >= EFFORT_VETO_OVERRIDE_MIN_DELTA_MGDL5M

        val digestionHigh = digestionRiseCore && (!effortBlocksMeal || effortVetoOverriddenByStrongMealRise)

        if (digestionHigh) {
            reasons.add(
                if (effortVetoOverriddenByStrongMealRise) {
                    "level_high_digestion_overrides_effort_veto"
                } else {
                    "level_high_digestion_rise"
                },
            )
            return MealCertaintyLevel.HIGH
        }

        val mealContext =
            treeState == MealCertaintyTreeState.DIGESTION_ACTIVE ||
                treeState == MealCertaintyTreeState.MEAL_PROBABLE ||
                phase.isActive ||
                input.mealBranchConfidence >= 0.55 ||
                (input.cobG >= 3.0 && input.mealBranchConfidence >= 0.50)

        if (
            mealContext &&
            (rise == MealRiseGeometry.OK || rise == MealRiseGeometry.WEAK) &&
            !effortBlocksMeal &&
            terminals != MealTerminalsAgree.HYPO_CONFLICT
        ) {
            reasons.add("level_med_meal_context")
            return MealCertaintyLevel.MED
        }

        if (
            mealContext ||
            input.mealRiseConfirmedLegacy ||
            input.mealBranchConfidence >= 0.45
        ) {
            reasons.add("level_low_weak_or_vetoed")
            return MealCertaintyLevel.LOW
        }

        reasons.add("level_none")
        return MealCertaintyLevel.NONE
    }
}
