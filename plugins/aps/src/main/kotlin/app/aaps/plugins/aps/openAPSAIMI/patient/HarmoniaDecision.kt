package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

data class HarmoniaDecisionEnvironment(
    val currentBgMgdl: Double,
    val deltaMgdl5m: Double,
    val iobU: Double,
    val cobG: Double,
    val currentBasalUph: Double,
    val maxBasalUph: Double,
    val maxSmbU: Double,
    val maxIobU: Double,
    val pumpBasalStepUph: Double = 0.05,
    val pumpSmbStepU: Double = 0.05,
    val sensorAgeMin: Int = 0,
    val sensorNoise: Double = 0.0,
    val mealRiseConfirmed: Boolean = false,
    val targetBgMgdl: Double? = null,
    val correctionFragilityScore: Double = 0.0,
    val postHyperExhaustionScore: Double = 0.0,
    val chaoticEpisodeLoad: Double = 0.0,
    val effectiveDiaHours: Double? = null,
    val effectivePeakMinutes: Double? = null,
    val seed: Long? = null,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("current_bg_mgdl", currentBgMgdl)
            put("delta_mgdl_5m", deltaMgdl5m)
            put("iob_u", iobU)
            put("cob_g", cobG)
            put("current_basal_uph", currentBasalUph)
            put("max_basal_uph", maxBasalUph)
            put("max_smb_u", maxSmbU)
            put("max_iob_u", maxIobU)
            put("pump_basal_step_uph", pumpBasalStepUph)
            put("pump_smb_step_u", pumpSmbStepU)
            put("sensor_age_min", sensorAgeMin)
            put("sensor_noise", sensorNoise)
            put("meal_rise_confirmed", mealRiseConfirmed)
            put("target_bg_mgdl", targetBgMgdl ?: JSONObject.NULL)
            put("correction_fragility_score", correctionFragilityScore)
            put("post_hyper_exhaustion_score", postHyperExhaustionScore)
            put("chaotic_episode_load", chaoticEpisodeLoad)
            effectiveDiaHours?.let { put("effective_dia_h", it) }
            effectivePeakMinutes?.let { put("effective_peak_min", it) }
            put("seed", seed ?: JSONObject.NULL)
        }
}

data class HarmoniaDecision(
    val timestampMs: Long,
    val branch: String,
    val action: HarmoniaAction,
    val eligible: Boolean,
    val targetBasalUph: Double,
    val targetSmbU: Double,
    val basalFactor: Double,
    val smbFactor: Double,
    val environment: HarmoniaDecisionEnvironment,
    val capsApplied: List<String>,
    val blockers: List<String>,
    val rationale: List<String>,
    val compactSummary: String,
    val version: Int = 1,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("timestamp", timestampMs)
            put("version", version)
            put("branch", branch)
            put("action", action.name)
            put("eligible", eligible)
            put("simulated_basal_uph", targetBasalUph)
            put("simulated_smb_u", targetSmbU)
            put("basal_factor", basalFactor)
            put("smb_factor", smbFactor)
            put("environment", environment.toJsonObject())
            put("caps_applied", JSONArray(capsApplied))
            put("blockers", JSONArray(blockers))
            put("rationale", JSONArray(rationale))
            put("compact_summary", compactSummary)
            put("simulation_only", true)
            put("applies_to_pump", false)
            put("source", "harmonia_simulation_branch_v1")
        }
}

data class HarmoniaProductionDecision(
    val timestampMs: Long,
    val mode: HarmoniaProductionMode,
    val selectedForProduction: Boolean,
    val requestedRateUph: Double?,
    val boundedRateUph: Double?,
    val appliedRateUph: Double?,
    val appliedDurationMin: Int?,
    val runtimeBlocker: String?,
    val safetyBlockers: List<String>,
    val sourceAction: HarmoniaAction?,
    val branch: String?,
    val reason: String,
    val version: Int = 1,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("timestamp", timestampMs)
            put("version", version)
            put("mode", mode.name)
            put("selected_for_production", selectedForProduction)
            put("requested_rate_uph", requestedRateUph ?: JSONObject.NULL)
            put("bounded_rate_uph", boundedRateUph ?: JSONObject.NULL)
            put("applied_rate_uph", appliedRateUph ?: JSONObject.NULL)
            put("applied_duration_min", appliedDurationMin ?: JSONObject.NULL)
            put("runtime_blocker", runtimeBlocker ?: JSONObject.NULL)
            put("safety_blockers", JSONArray(safetyBlockers))
            put("source_action", sourceAction?.name ?: JSONObject.NULL)
            put("branch", branch ?: JSONObject.NULL)
            put("reason", reason)
            put("basal_first_only", true)
            put("adds_smb_authority", false)
            put("applies_to_pump", mode == HarmoniaProductionMode.APPLIED)
            put("source", "harmonia_production_branch_v1")
        }
}

enum class HarmoniaProductionMode {
    SKIPPED,
    BLOCKED,
    READY,
    APPLIED,
}

enum class HarmoniaAction {
    OBSERVE,
    BASAL_FIRST,
    MEAL_SUPPORT,
    PROTECTIVE_REDUCTION,
    STABILIZE,
    BLOCKED,
}

internal object HarmoniaDecisionEngine {

    /**
     * Sensor warmup window, in minutes since insertion, during which fresh CGM readings are
     * unreliable and Harmonia must stand down. Note that [HarmoniaDecisionEnvironment.sensorAgeMin]
     * carries the real sensor age (minutes since the last SENSOR_CHANGE event), so an established
     * sensor reports thousands of minutes and is *not* in warmup. A value of `0` means the insertion
     * time is unknown (no SENSOR_CHANGE recorded) and is likewise treated as established, never warmup.
     */
    private const val SENSOR_WARMUP_MAX_MIN = 120

    /**
     * H4 rising-delta floor — aligned with [declaredMealRise] (`delta >= 0.8`). Without this guard,
     * field replay showed ~48% of H4 flips on falling BG (post-peak), which must stay protective.
     */
    internal const val H4_MIN_RISING_DELTA_MGDL = 0.8

    fun evaluate(
        tree: PhysiologicalTreeSnapshot?,
        environment: HarmoniaDecisionEnvironment?,
        timestampMs: Long = tree?.timestamp ?: 0L,
    ): HarmoniaDecision? {
        if (tree == null || environment == null) return null

        val blockers = buildBlockers(tree, environment)
        val capsApplied = mutableListOf<String>()
        val action = chooseAction(tree, environment, blockers)
        val branch = tree.trunk.globalState.name

        val rawBasalFactor = when (action) {
            HarmoniaAction.BASAL_FIRST -> 1.18
            HarmoniaAction.MEAL_SUPPORT -> 1.10
            HarmoniaAction.PROTECTIVE_REDUCTION -> 0.70
            HarmoniaAction.STABILIZE -> 0.85
            HarmoniaAction.OBSERVE,
            HarmoniaAction.BLOCKED,
            -> 1.0
        }
        val rawSmbFactor = when (action) {
            HarmoniaAction.MEAL_SUPPORT -> 0.30
            HarmoniaAction.BASAL_FIRST -> 0.0
            else -> 0.0
        }

        val basalFactor = rawBasalFactor.coerceIn(0.0, 1.25)
        if (basalFactor != rawBasalFactor) capsApplied.add("basal_factor_cap")
        val smbFactor = rawSmbFactor.coerceIn(0.0, 0.35)
        if (smbFactor != rawSmbFactor) capsApplied.add("smb_factor_cap")

        val requestedBasal = environment.currentBasalUph * basalFactor
        val simulatedBasal = roundToStep(
            requestedBasal.coerceIn(0.0, environment.maxBasalUph.coerceAtLeast(0.0)),
            environment.pumpBasalStepUph,
        )
        if (simulatedBasal != requestedBasal) capsApplied.add("pump_basal_cap_or_step")

        val iobSpace = (environment.maxIobU - environment.iobU).coerceAtLeast(0.0)
        val requestedSmb = environment.maxSmbU.coerceAtLeast(0.0) * smbFactor
        val simulatedSmb = roundToStep(
            requestedSmb.coerceIn(0.0, minOf(environment.maxSmbU.coerceAtLeast(0.0), iobSpace)),
            environment.pumpSmbStepU,
        )
        if (simulatedSmb != requestedSmb) capsApplied.add("pump_smb_or_iob_cap")

        val eligible = blockers.isEmpty() && action != HarmoniaAction.BLOCKED
        val safeBasal = if (eligible) simulatedBasal else environment.currentBasalUph
        val safeSmb = if (eligible) simulatedSmb else 0.0
        val rationale = buildRationale(tree, environment, action, blockers)
        val summary = buildSummary(action, branch, eligible, safeBasal, safeSmb, blockers)

        return HarmoniaDecision(
            timestampMs = timestampMs,
            branch = branch,
            action = if (eligible) action else HarmoniaAction.BLOCKED,
            eligible = eligible,
            targetBasalUph = safeBasal,
            targetSmbU = safeSmb,
            basalFactor = if (eligible) basalFactor else 1.0,
            smbFactor = if (eligible) smbFactor else 0.0,
            environment = environment,
            capsApplied = capsApplied.distinct(),
            blockers = blockers,
            rationale = rationale,
            compactSummary = summary,
        )
    }

    fun randomizedEnvironment(
        seed: Long,
        currentBasalUph: Double = 1.0,
        maxBasalUph: Double = 5.0,
        maxSmbU: Double = 1.0,
        maxIobU: Double = 5.0,
    ): HarmoniaDecisionEnvironment {
        val random = Random(seed)
        return HarmoniaDecisionEnvironment(
            currentBgMgdl = random.nextDouble(65.0, 290.0),
            deltaMgdl5m = random.nextDouble(-5.0, 8.0),
            iobU = random.nextDouble(0.0, maxIobU.coerceAtLeast(0.1)),
            cobG = random.nextDouble(0.0, 80.0),
            currentBasalUph = currentBasalUph,
            maxBasalUph = maxBasalUph,
            maxSmbU = maxSmbU,
            maxIobU = maxIobU,
            sensorAgeMin = random.nextInt(0, 15),
            sensorNoise = random.nextDouble(0.0, 1.0),
            seed = seed,
        )
    }

    private fun buildBlockers(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
    ): List<String> =
        buildList {
            if (env.sensorAgeMin in 1..SENSOR_WARMUP_MAX_MIN) add("sensor_warmup")
            if (env.sensorNoise >= 0.75) add("sensor_noise")
            if (tree.branches.sensorTrust.confidence < 0.40) add("sensor_uncertain")
            if (tree.branches.hypoRisk.confidence >= 0.45) add("hypo_or_recovery")
            if (env.currentBgMgdl < 80.0 && env.deltaMgdl5m <= 0.0) add("low_or_falling_bg")
            if (env.iobU >= env.maxIobU * 0.92) add("max_iob_pressure")
            if (tree.trunk.riskLevel == PhysiologicalRiskLevel.CRITICAL) add("critical_risk")
        }

    private fun chooseAction(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
        blockers: List<String>,
    ): HarmoniaAction {
        if (blockers.isNotEmpty()) return HarmoniaAction.BLOCKED

        val fragility = env.correctionFragilityScore.coerceIn(0.0, 1.0)
        val exhaustion = env.postHyperExhaustionScore.coerceIn(0.0, 1.0)
        val chaotic = env.chaoticEpisodeLoad.coerceIn(0.0, 1.0)
        if (fragility >= 0.55 || exhaustion >= 0.65 || chaotic >= 0.50) {
            return HarmoniaAction.STABILIZE
        }

        // H4 meal-rise bridge: digestion + confirmed rise above band beats activity/post-activity
        // PROTECTIVE_REDUCTION (which otherwise wins first and starves meal support).
        if (prefersMealSupportOverProtective(tree, env)) {
            return HarmoniaAction.MEAL_SUPPORT
        }

        if (tree.branches.activity.confidence >= 0.55 || tree.branches.postActivity.confidence >= 0.45) {
            return HarmoniaAction.PROTECTIVE_REDUCTION
        }

        val mealConfidence = tree.branches.meal.confidence
        val undeclaredMealRise =
            mealConfidence >= 0.55 &&
                env.cobG < 1.0 &&
                (env.deltaMgdl5m >= 1.0 || env.mealRiseConfirmed)
        val declaredMealRise =
            env.cobG >= 3.0 &&
                mealConfidence >= 0.50 &&
                env.deltaMgdl5m >= 0.8
        if (undeclaredMealRise || declaredMealRise) {
            return HarmoniaAction.MEAL_SUPPORT
        }

        if (
            tree.branches.hormonalResistance.confidence >= 0.55 ||
            tree.branches.stress.confidence >= 0.55 ||
            tree.branches.insulinEffectiveness.confidence >= 0.55
        ) {
            return HarmoniaAction.BASAL_FIRST
        }
        return HarmoniaAction.OBSERVE
    }

    /**
     * H4: when the trunk is [GlobalPhysiologicalState.DIGESTION_ACTIVE], meal-rise is confirmed,
     * BG is above target + [PostHypoAggressiveRiseExit.TARGET_MARGIN_MGDL], and delta is still
     * rising ([H4_MIN_RISING_DELTA_MGDL]), prefer [HarmoniaAction.MEAL_SUPPORT] over
     * [HarmoniaAction.PROTECTIVE_REDUCTION]. Falling/flat post-peak stays protective.
     */
    internal fun prefersMealSupportOverProtective(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
    ): Boolean {
        if (tree.trunk.globalState != GlobalPhysiologicalState.DIGESTION_ACTIVE) return false
        if (!env.mealRiseConfirmed) return false
        val target = env.targetBgMgdl ?: return false
        if (!target.isFinite() || !env.currentBgMgdl.isFinite() || !env.deltaMgdl5m.isFinite()) return false
        if (env.deltaMgdl5m < H4_MIN_RISING_DELTA_MGDL) return false
        return env.currentBgMgdl > target + PostHypoAggressiveRiseExit.TARGET_MARGIN_MGDL
    }

    private fun buildRationale(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
        action: HarmoniaAction,
        blockers: List<String>,
    ): List<String> =
        buildList {
            add("tree=${tree.trunk.globalState.name} conf=${pct(tree.trunk.confidence)} risk=${tree.trunk.riskLevel.name}")
            add("bg=${env.currentBgMgdl.roundToInt()} delta=${fmt(env.deltaMgdl5m)} iob=${fmt(env.iobU)}/${fmt(env.maxIobU)}")
            if (blockers.isNotEmpty()) {
                add("blocked=${blockers.joinToString(",")}")
            } else {
                add("simulation_action=${action.name.lowercase(Locale.US)}")
                if (action == HarmoniaAction.MEAL_SUPPORT && prefersMealSupportOverProtective(tree, env)) {
                    add("h4_meal_rise_bridge")
                }
            }
        }

    private fun buildSummary(
        action: HarmoniaAction,
        branch: String,
        eligible: Boolean,
        basal: Double,
        smb: Double,
        blockers: List<String>,
    ): String {
        if (!eligible) {
            return "Harmonia sim: blocked $branch | ${blockers.joinToString(",")}"
        }
        return "Harmonia sim: ${action.name.lowercase(Locale.US)} $branch | basal ${fmt(basal)}U/h | smb ${fmt(smb)}U"
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (!value.isFinite()) return 0.0
        if (!step.isFinite() || step <= 0.0) return value
        return ((value / step).roundToInt() * step).coerceAtLeast(0.0)
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun pct(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100.0).roundToInt()}%"
}
