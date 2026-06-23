package app.aaps.plugins.aps.openAPSAIMI.patient

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

data class HarmoniaSimulationEnvironment(
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
    val correctionFragilityScore: Double = 0.0,
    val postHyperExhaustionScore: Double = 0.0,
    val chaoticEpisodeLoad: Double = 0.0,
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
            put("correction_fragility_score", correctionFragilityScore)
            put("post_hyper_exhaustion_score", postHyperExhaustionScore)
            put("chaotic_episode_load", chaoticEpisodeLoad)
            put("seed", seed ?: JSONObject.NULL)
        }
}

data class HarmoniaSimulationDecision(
    val timestampMs: Long,
    val branch: String,
    val action: HarmoniaSimulationAction,
    val eligible: Boolean,
    val simulatedBasalUph: Double,
    val simulatedSmbU: Double,
    val basalFactor: Double,
    val smbFactor: Double,
    val environment: HarmoniaSimulationEnvironment,
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
            put("simulated_basal_uph", simulatedBasalUph)
            put("simulated_smb_u", simulatedSmbU)
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
    val sourceAction: HarmoniaSimulationAction?,
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

enum class HarmoniaSimulationAction {
    OBSERVE,
    BASAL_FIRST,
    MEAL_SUPPORT,
    PROTECTIVE_REDUCTION,
    STABILIZE,
    BLOCKED,
}

internal object HarmoniaSimulationEngine {

    fun evaluate(
        tree: PhysiologicalTreeSnapshot?,
        environment: HarmoniaSimulationEnvironment?,
        timestampMs: Long = tree?.timestamp ?: 0L,
    ): HarmoniaSimulationDecision? {
        if (tree == null || environment == null) return null

        val blockers = buildBlockers(tree, environment)
        val capsApplied = mutableListOf<String>()
        val action = chooseAction(tree, environment, blockers)
        val branch = tree.trunk.globalState.name

        val rawBasalFactor = when (action) {
            HarmoniaSimulationAction.BASAL_FIRST -> 1.18
            HarmoniaSimulationAction.MEAL_SUPPORT -> 1.10
            HarmoniaSimulationAction.PROTECTIVE_REDUCTION -> 0.70
            HarmoniaSimulationAction.STABILIZE -> 0.85
            HarmoniaSimulationAction.OBSERVE,
            HarmoniaSimulationAction.BLOCKED,
            -> 1.0
        }
        val rawSmbFactor = when (action) {
            HarmoniaSimulationAction.MEAL_SUPPORT -> 0.30
            HarmoniaSimulationAction.BASAL_FIRST -> 0.0
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

        val eligible = blockers.isEmpty() && action != HarmoniaSimulationAction.BLOCKED
        val safeBasal = if (eligible) simulatedBasal else environment.currentBasalUph
        val safeSmb = if (eligible) simulatedSmb else 0.0
        val rationale = buildRationale(tree, environment, action, blockers)
        val summary = buildSummary(action, branch, eligible, safeBasal, safeSmb, blockers)

        return HarmoniaSimulationDecision(
            timestampMs = timestampMs,
            branch = branch,
            action = if (eligible) action else HarmoniaSimulationAction.BLOCKED,
            eligible = eligible,
            simulatedBasalUph = safeBasal,
            simulatedSmbU = safeSmb,
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
    ): HarmoniaSimulationEnvironment {
        val random = Random(seed)
        return HarmoniaSimulationEnvironment(
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
        env: HarmoniaSimulationEnvironment,
    ): List<String> =
        buildList {
            if (env.sensorAgeMin > 10) add("sensor_age")
            if (env.sensorNoise >= 0.75) add("sensor_noise")
            if (tree.branches.sensorTrust.confidence < 0.40) add("sensor_uncertain")
            if (tree.branches.hypoRisk.confidence >= 0.45) add("hypo_or_recovery")
            if (env.currentBgMgdl < 80.0 && env.deltaMgdl5m <= 0.0) add("low_or_falling_bg")
            if (env.iobU >= env.maxIobU * 0.92) add("max_iob_pressure")
            if (tree.trunk.riskLevel == PhysiologicalRiskLevel.CRITICAL) add("critical_risk")
        }

    private fun chooseAction(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaSimulationEnvironment,
        blockers: List<String>,
    ): HarmoniaSimulationAction {
        if (blockers.isNotEmpty()) return HarmoniaSimulationAction.BLOCKED

        val fragility = env.correctionFragilityScore.coerceIn(0.0, 1.0)
        val exhaustion = env.postHyperExhaustionScore.coerceIn(0.0, 1.0)
        val chaotic = env.chaoticEpisodeLoad.coerceIn(0.0, 1.0)
        if (fragility >= 0.55 || exhaustion >= 0.65 || chaotic >= 0.50) {
            return HarmoniaSimulationAction.STABILIZE
        }

        if (tree.branches.activity.confidence >= 0.55 || tree.branches.postActivity.confidence >= 0.45) {
            return HarmoniaSimulationAction.PROTECTIVE_REDUCTION
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
            return HarmoniaSimulationAction.MEAL_SUPPORT
        }

        if (
            tree.branches.hormonalResistance.confidence >= 0.55 ||
            tree.branches.stress.confidence >= 0.55 ||
            tree.branches.insulinEffectiveness.confidence >= 0.55
        ) {
            return HarmoniaSimulationAction.BASAL_FIRST
        }
        return HarmoniaSimulationAction.OBSERVE
    }

    private fun buildRationale(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaSimulationEnvironment,
        action: HarmoniaSimulationAction,
        blockers: List<String>,
    ): List<String> =
        buildList {
            add("tree=${tree.trunk.globalState.name} conf=${pct(tree.trunk.confidence)} risk=${tree.trunk.riskLevel.name}")
            add("bg=${env.currentBgMgdl.roundToInt()} delta=${fmt(env.deltaMgdl5m)} iob=${fmt(env.iobU)}/${fmt(env.maxIobU)}")
            if (blockers.isNotEmpty()) {
                add("blocked=${blockers.joinToString(",")}")
            } else {
                add("simulation_action=${action.name.lowercase(Locale.US)}")
            }
        }

    private fun buildSummary(
        action: HarmoniaSimulationAction,
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
