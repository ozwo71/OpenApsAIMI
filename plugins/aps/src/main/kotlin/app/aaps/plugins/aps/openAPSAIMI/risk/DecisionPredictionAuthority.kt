package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonalScenarioTerminalCap
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.max

enum class DecisionPredictionSource {
    PKPD_ONLY,
    SCENARIO_CONSENSUS,
    SCENARIO_GUARDED_UPLIFT,
    SCENARIO_MEAL_UPLIFT,
    SCENARIO_TRAJECTORY_UPLIFT,
    SCENARIO_SUPPRESSED_NON_MEAL,
}

data class DecisionPredictionAuthority(
    val predTerminalMgdl: Double,
    val eventualTerminalMgdl: Double,
    val pkpdEventualMgdl: Double,
    val scenarioFloorTerminalMgdl: Double?,
    val scenarioBestTerminalMgdl: Double?,
    val source: DecisionPredictionSource,
    val scenarioUpliftApplied: Boolean,
    val falseMealSuppression: Boolean,
    val reason: String,
)

object DecisionPredictionAuthorityResolver {

    fun resolve(
        bgMgdl: Double,
        pkpdEventualMgdl: Double,
        scenarioProjection: ScenarioProjectionPair?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        hypothesisState: UamHypothesisState?,
        latentState: PhysioLatentState?,
        causalStatePosterior: CausalStatePosterior?,
        trajectoryAnalysis: TrajectoryAnalysis?,
        physioPolicy: BehavioralRiskPolicy?,
        uamConfidence: Double,
        postHypoDelivery: PostHypoDeliveryAuthority.Decision = PostHypoDeliveryAuthority.INACTIVE,
    ): DecisionPredictionAuthority {
        val pkpd = pkpdEventualMgdl.takeIf { it.isFinite() } ?: bgMgdl
        val rawScenarioFloor = scenarioProjection?.clinicalFloor?.terminalMgdl?.takeIf { it.isFinite() }
        val rawScenarioBest = scenarioProjection?.scenarioBest?.terminalMgdl?.takeIf { it.isFinite() }
        val scenarioBest = rawScenarioBest?.let {
            HormonalScenarioTerminalCap.capBestTerminalMgdl(
                bgMgdl = bgMgdl,
                bestTerminalMgdl = it,
                policy = physioPolicy,
            )
        }
        val predTerminal = rawScenarioFloor ?: pkpd
        val causalMealConfidence = causalStatePosterior?.mealConfidence ?: 0.0
        val causalProtectiveConfidence = causalStatePosterior?.protectiveConfidence ?: 0.0
        val causalDominant = causalStatePosterior?.dominant ?: CausalStateId.UNKNOWN
        val posteriorSuppressMeal =
            causalDominant in setOf(
                CausalStateId.DAWN_ENDOGENOUS,
                CausalStateId.POST_HYPO_RECOVERY,
                CausalStateId.STRESS_RESISTANCE,
                CausalStateId.INFLAMMATORY_DRIFT,
                CausalStateId.ABSORPTION_UNCERTAIN,
            ) &&
                causalProtectiveConfidence >= causalMealConfidence + 0.08 &&
                (causalStatePosterior?.dominantConfidence ?: 0.0) >= 0.60
        val falseMealSuppression =
            postHypoDelivery.forceMealInterpretationSuppressed ||
                hypothesisState?.suppressMealInterpretation == true ||
                latentState?.falseMealSuppression == true ||
                physioPolicy?.suppressMealLikeScenario == true ||
                posteriorSuppressMeal

        if (scenarioBest == null || rawScenarioFloor == null) {
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = pkpd,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.PKPD_ONLY,
                scenarioUpliftApplied = false,
                falseMealSuppression = falseMealSuppression,
                reason = "no_scenario_projection",
            )
        }

        if (postHypoDelivery.active && postHypoDelivery.forceMealInterpretationSuppressed) {
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = pkpd,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL,
                scenarioUpliftApplied = false,
                falseMealSuppression = true,
                reason = "post_hypo_delivery_guard ${postHypoDelivery.reasonTag}",
            )
        }

        val scenarioLead = scenarioBest - pkpd
        val mealPhaseActive = mealAbsorptionOutput?.phase?.isActive == true
        val mealDeliveryPriority = mealAbsorptionOutput?.mealDeliveryPriority == true
        val mealCompatibleProb = max(hypothesisState?.mealCompatibleProb() ?: 0.0, causalMealConfidence)
        val competingNonMealProb = max(hypothesisState?.competingNonMealProb() ?: 0.0, causalProtectiveConfidence)
        val trajectoryType = trajectoryAnalysis?.classification
        val trajectorySupportsUplift =
            trajectoryType == TrajectoryType.OPEN_DIVERGING ||
                trajectoryType == TrajectoryType.SLOW_DRIFT
        val strongRiseProjection = scenarioBest > bgMgdl + if (causalDominant == CausalStateId.FAST_MEAL) 12.0 else 15.0
        val mealEvidence =
            mealPhaseActive ||
                mealDeliveryPriority ||
                mealCompatibleProb >= 0.55 ||
                causalMealConfidence >= 0.55 ||
                uamConfidence >= 0.45

        if (scenarioLead <= 5.0) {
            val consensus = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = consensus,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_CONSENSUS,
                scenarioUpliftApplied = false,
                falseMealSuppression = falseMealSuppression,
                reason = "scenario_consensus lead=${"%.1f".format(scenarioLead)}",
            )
        }

        if (falseMealSuppression && competingNonMealProb >= 0.60) {
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = pkpd,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL,
                scenarioUpliftApplied = false,
                falseMealSuppression = true,
                reason = "non_meal_guard prob=${"%.2f".format(competingNonMealProb)} cause=${causalDominant.name}",
            )
        }

        if (strongRiseProjection && mealEvidence && scenarioLead >= if (causalDominant == CausalStateId.FAST_MEAL) 12.0 else 15.0) {
            val uplift = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = uplift,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_MEAL_UPLIFT,
                scenarioUpliftApplied = uplift > pkpd + 0.5,
                falseMealSuppression = falseMealSuppression,
                reason = "meal_evidence phase=${mealAbsorptionOutput?.phase?.name ?: "NONE"} " +
                    "lead=${"%.1f".format(scenarioLead)} cause=${causalDominant.name}",
            )
        }

        if (!falseMealSuppression && strongRiseProjection && trajectorySupportsUplift && scenarioLead >= 20.0) {
            val uplift = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = uplift,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_TRAJECTORY_UPLIFT,
                scenarioUpliftApplied = uplift > pkpd + 0.5,
                falseMealSuppression = falseMealSuppression,
                reason = "trajectory=${trajectoryType.name} lead=${"%.1f".format(scenarioLead)}",
            )
        }

        if (!falseMealSuppression && strongRiseProjection && (mealEvidence || trajectorySupportsUplift) && scenarioLead >= 10.0) {
            val uplift = max(pkpd, scenarioBest)
            return DecisionPredictionAuthority(
                predTerminalMgdl = predTerminal,
                eventualTerminalMgdl = uplift,
                pkpdEventualMgdl = pkpd,
                scenarioFloorTerminalMgdl = rawScenarioFloor,
                scenarioBestTerminalMgdl = scenarioBest,
                source = DecisionPredictionSource.SCENARIO_GUARDED_UPLIFT,
                scenarioUpliftApplied = uplift > pkpd + 0.5,
                falseMealSuppression = falseMealSuppression,
                reason = "guarded_uplift lead=${"%.1f".format(scenarioLead)} meal=${mealEvidence} traj=${trajectoryType?.name ?: "NONE"}",
            )
        }

        return DecisionPredictionAuthority(
            predTerminalMgdl = predTerminal,
            eventualTerminalMgdl = pkpd,
            pkpdEventualMgdl = pkpd,
            scenarioFloorTerminalMgdl = rawScenarioFloor,
            scenarioBestTerminalMgdl = scenarioBest,
            source = DecisionPredictionSource.PKPD_ONLY,
            scenarioUpliftApplied = false,
            falseMealSuppression = falseMealSuppression,
            reason = "pkpd_retained lead=${"%.1f".format(scenarioLead)}",
        )
    }

    fun formatLogLine(authority: DecisionPredictionAuthority): String =
        "PRED_AUTHORITY: src=${authority.source.name} predT=${authority.predTerminalMgdl.toInt()} " +
            "evT=${authority.eventualTerminalMgdl.toInt()} pkpd=${authority.pkpdEventualMgdl.toInt()} " +
            "best=${authority.scenarioBestTerminalMgdl?.toInt() ?: "-"} " +
            "mealSupp=${authority.falseMealSuppression} uplift=${authority.scenarioUpliftApplied} " +
            authority.reason
}
