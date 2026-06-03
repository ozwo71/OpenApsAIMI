package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskConstants
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoConstants
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the two authoritative AIMI scenario curves from PKPD primitives + product context.
 *
 * Pure Kotlin — no side effects. See [docs/AIMI_SCENARIO_PROJECTION.md].
 */
object ScenarioProjectionEngine {

    private const val TRAJECTORY_RISE_STEP_FACTOR = 0.12
    private const val SPIRAL_DAMPING = 0.94
    private const val ACTIVITY_RISE_CAP_FACTOR = 0.65
    private const val PHYSIO_DAMP_BLEND = 0.35

    fun build(input: ScenarioProjectionInput): ScenarioProjectionPair {
        val curves = input.curves
        val ctx = input.context
        val contributors = mutableListOf<ScenarioContributor>()
        val floorRaw = curves.iob.toMutableList()
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.PKPD_IOB_FLOOR,
                summary = "Clinical floor = PKPD insulin-only path",
            ),
        )

        val bestRaw = curves.hybrid.toMutableList()
        mergeMealAndUamSources(bestRaw, curves, ctx, contributors)
        ctx.trajectoryAnalysis?.let { analysis ->
            if (ctx.trajectoryModulationActive || ctx.trajectoryRelevanceScore > 0.25) {
                applyTrajectoryLayer(bestRaw, input.bgNowMgdl, input.deltaMgdlPer5, analysis, contributors)
            }
        }
        if (ctx.activityProtectionMode || ctx.contextActivityActive) {
            applyActivityLayer(bestRaw, input.bgNowMgdl, input.deltaMgdlPer5, contributors)
        }
        if (ctx.physioReactivityFactor < 0.98 || ctx.physioSmbFactor < 0.98) {
            applyPhysioLayer(
                bestRaw,
                input.bgNowMgdl,
                ctx.physioSmbFactor,
                ctx.physioReactivityFactor,
                contributors,
            )
        }
        if (ctx.suppressMealLikeUam || ctx.scenarioBestCapAboveBgMgdl != null) {
            applyPhysiologicalPhaseLayer(
                bestRaw,
                input.bgNowMgdl,
                ctx,
                contributors,
            )
        }
        if (ctx.contextSmbFactor < 0.98f) {
            applyContextSmbLayer(bestRaw, input.bgNowMgdl, ctx.contextSmbFactor, contributors)
        }
        blendTowardTarget(bestRaw, input.bgNowMgdl, ctx.targetBgMgdl, ctx.trajectoryType, contributors)

        return ScenarioProjectionPair(
            clinicalFloor = ScenarioProjectionCurve.fromRawPoints(ScenarioProjectionKind.CLINICAL_FLOOR, floorRaw),
            scenarioBest = ScenarioProjectionCurve.fromRawPoints(ScenarioProjectionKind.SCENARIO_BEST, bestRaw),
            contributors = contributors,
            cobPointsMgdl = clampSeriesToInts(curves.cob),
            ztPointsMgdl = clampSeriesToInts(curves.zt),
            trajectoryType = ctx.trajectoryType?.name,
        )
    }

    private fun mergeMealAndUamSources(
        bestRaw: MutableList<Double>,
        curves: AdvancedPredictionCurves,
        ctx: ScenarioProjectionContext,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val terminalBefore = curves.hybrid.lastOrNull() ?: bestRaw.last()
        for (i in bestRaw.indices) {
            var candidate = bestRaw[i]
            val uam = curves.uam.getOrElse(i) { candidate }
            val cob = curves.cob.getOrElse(i) { candidate }
            if (ctx.mealIntent) {
                candidate = max(candidate, max(uam, cob))
            } else if (!ctx.suppressMealLikeUam && uam > candidate + 5.0) {
                candidate = max(candidate, uam)
            }
            bestRaw[i] = candidate
        }
        val terminalLift = bestRaw.last() - terminalBefore
        if (ctx.mealIntent) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.MEAL_CONTEXT,
                    summary = "Meal intent — max(UAM, COB) over hybrid",
                    terminalDeltaMgdl = terminalLift,
                ),
            )
        }
        if (ctx.effectiveCobG > 0.0 && ctx.mealContext.mealAdvisorCarbsFresh) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.MEAL_ADVISOR_COB,
                    summary = "Advisor COB ${"%.0f".format(ctx.effectiveCobG)}g in COB curve",
                ),
            )
        }
        if (!ctx.mealIntent && !ctx.suppressMealLikeUam && terminalLift > 5.0) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.PKPD_UAM_MOMENTUM,
                    summary = "UAM momentum uplift vs hybrid",
                    terminalDeltaMgdl = terminalLift,
                ),
            )
        }
    }

    private fun applyPhysiologicalPhaseLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        ctx: ScenarioProjectionContext,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val before = bestRaw.last()
        val capAbove = ctx.scenarioBestCapAboveBgMgdl
        if (capAbove != null && capAbove.isFinite()) {
            val cap = bg + capAbove
            for (i in bestRaw.indices) {
                if (bestRaw[i] > cap) {
                    bestRaw[i] = cap
                }
            }
        }
        if (ctx.suppressMealLikeUam) {
            val hormonalBlend = 0.88
            for (i in 1 until bestRaw.size) {
                bestRaw[i] = bg + (bestRaw[i] - bg) * hormonalBlend
            }
        }
        val delta = bestRaw.last() - before
        if (abs(delta) > 0.5 || ctx.suppressMealLikeUam) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.PHYSIOLOGICAL_PHASE,
                    summary = "Phase ${ctx.physiologicalPhase.name} cap=${ctx.scenarioBestCapAboveBgMgdl?.toInt() ?: "-"} " +
                        "uamOff=${ctx.suppressMealLikeUam}",
                    terminalDeltaMgdl = delta,
                ),
            )
        }
    }

    private fun applyTrajectoryLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        delta: Float,
        analysis: TrajectoryAnalysis,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val beforeTerminal = bestRaw.last()
        when (analysis.classification) {
            TrajectoryType.OPEN_DIVERGING,
            TrajectoryType.SLOW_DRIFT,
            -> {
                val openness = analysis.metrics.openness.coerceIn(0.2, 1.0)
                val risePerStep = delta.toDouble() * TRAJECTORY_RISE_STEP_FACTOR * openness
                if (risePerStep > 0.0) {
                    for (i in 1 until bestRaw.size) {
                        val bias = risePerStep * (i.toDouble() / bestRaw.size.coerceAtLeast(1))
                        bestRaw[i] = (bestRaw[i] + bias).coerceAtMost(AimiRiskConstants.NUMERIC_CEILING_MGDL)
                    }
                    contributors.add(
                        ScenarioContributor(
                            id = ScenarioContributorId.TRAJECTORY_RISE,
                            summary = "${analysis.classification.name} openness=${"%.2f".format(openness)}",
                            terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
                        ),
                    )
                }
            }
            TrajectoryType.TIGHT_SPIRAL -> {
                for (i in 1 until bestRaw.size) {
                    bestRaw[i] = bg + (bestRaw[i] - bg) * SPIRAL_DAMPING
                }
                contributors.add(
                    ScenarioContributor(
                        id = ScenarioContributorId.TRAJECTORY_SPIRAL_DAMP,
                        summary = "TIGHT_SPIRAL projection damping κ=${"%.2f".format(analysis.metrics.curvature)}",
                        terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
                    ),
                )
            }
            TrajectoryType.CLOSING_CONVERGING,
            TrajectoryType.STABLE_ORBIT,
            -> {
                val targetPull = 0.04
                for (i in 1 until bestRaw.size) {
                    val targetBlend = bg + (bestRaw[i] - bg) * (1.0 - targetPull * i / bestRaw.size)
                    bestRaw[i] = (bestRaw[i] * 0.85 + targetBlend * 0.15)
                }
                contributors.add(
                    ScenarioContributor(
                        id = ScenarioContributorId.TRAJECTORY_CONVERGENCE,
                        summary = analysis.classification.name,
                        terminalDeltaMgdl = bestRaw.last() - beforeTerminal,
                    ),
                )
            }
            TrajectoryType.HOVERING,
            TrajectoryType.UNCERTAIN,
            -> Unit
        }
    }

    private fun applyActivityLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        delta: Float,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val before = bestRaw.last()
        if (delta > 0f) {
            for (i in 1 until bestRaw.size) {
                val capped = bg + (bestRaw[i] - bg) * ACTIVITY_RISE_CAP_FACTOR
                bestRaw[i] = min(bestRaw[i], capped)
            }
        } else {
            for (i in 1 until bestRaw.size) {
                bestRaw[i] = bestRaw[i] - 2.0 * (i.toDouble() / bestRaw.size)
            }
        }
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.ACTIVITY_PROTECTION,
                summary = "Activity protection — capped rise / hypo cushion",
                terminalDeltaMgdl = bestRaw.last() - before,
            ),
        )
    }

    private fun applyPhysioLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        physioSmbFactor: Double,
        physioReactivityFactor: Double,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val before = bestRaw.last()
        val damp = min(physioSmbFactor, physioReactivityFactor).coerceIn(0.85, 1.0)
        val blend = 1.0 - (1.0 - damp) * PHYSIO_DAMP_BLEND
        for (i in 1 until bestRaw.size) {
            bestRaw[i] = bg + (bestRaw[i] - bg) * blend
        }
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.PHYSIO_REACTIVITY,
                summary = "Physio damp smb=${"%.2f".format(physioSmbFactor)} react=${"%.2f".format(physioReactivityFactor)}",
                terminalDeltaMgdl = bestRaw.last() - before,
            ),
        )
    }

    private fun applyContextSmbLayer(
        bestRaw: MutableList<Double>,
        bg: Double,
        contextSmbFactor: Float,
        contributors: MutableList<ScenarioContributor>,
    ) {
        val before = bestRaw.last()
        val blend = contextSmbFactor.toDouble().coerceIn(0.5, 1.1)
        for (i in 1 until bestRaw.size) {
            bestRaw[i] = bg + (bestRaw[i] - bg) * blend
        }
        contributors.add(
            ScenarioContributor(
                id = ScenarioContributorId.CONTEXT_MODULE,
                summary = "Context module SMB×${"%.2f".format(contextSmbFactor)}",
                terminalDeltaMgdl = bestRaw.last() - before,
            ),
        )
    }

    private fun blendTowardTarget(
        bestRaw: MutableList<Double>,
        bg: Double,
        targetBg: Double,
        trajectoryType: TrajectoryType?,
        contributors: MutableList<ScenarioContributor>,
    ) {
        if (trajectoryType == TrajectoryType.OPEN_DIVERGING || trajectoryType == TrajectoryType.SLOW_DRIFT) {
            return
        }
        val before = bestRaw.last()
        val horizonWeight = 0.08
        for (i in 1 until bestRaw.size) {
            val w = horizonWeight * i / bestRaw.size
            bestRaw[i] = bestRaw[i] * (1.0 - w) + targetBg * w
        }
        if (abs(bestRaw.last() - before) > 1.0) {
            contributors.add(
                ScenarioContributor(
                    id = ScenarioContributorId.TARGET_BLEND,
                    summary = "Soft blend toward target ${targetBg.toInt()} mg/dL",
                    terminalDeltaMgdl = bestRaw.last() - before,
                ),
            )
        }
    }

    internal fun clampSeriesToInts(points: List<Double>): List<Int> =
        points.map { point ->
            point.coerceIn(AimiRiskConstants.NUMERIC_FLOOR_MGDL, AimiRiskConstants.NUMERIC_CEILING_MGDL).toInt()
        }

    internal fun isMealRiseConfirmed(
        bg: Double,
        delta: Float,
        ctx: ScenarioProjectionContext,
    ): Boolean =
        ctx.mealIntent ||
            (delta >= PredictiveHypoConstants.RISING_MODERATE_DELTA.toFloat() && bg >= 90.0)
}
