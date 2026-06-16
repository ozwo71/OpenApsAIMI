package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionContext
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionEngine
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoConstants

/**
 * Meal-aware safety composite for [resolveSafetyStart] (Phase 4B).
 * Replaces insulin-only eventual floor artefact with UAM terminal when meal rise is confirmed.
 */
data class SafetyPredictionTerminals(
    val predBg: Double,
    val eventualBg: Double,
    val uamTerminalMgdl: Double?,
    val mealRiseConfirmed: Boolean,
    val compositeMinMgdl: Double,
)

object SafetyPredictionTerminalsResolver {

    fun resolve(
        bg: Double,
        delta: Float,
        sanityPred: Double,
        sanityEventual: Double,
        uamTerminal: Double?,
        mealContext: MealSafetyContext,
    ): SafetyPredictionTerminals {
        val mealRiseConfirmed = isMealRiseConfirmed(bg, delta, mealContext)
        val (adjPred, adjEventual) = adjustTerminals(
            bg = bg,
            delta = delta,
            pred = sanityPred,
            eventual = sanityEventual,
            uamTerminal = uamTerminal,
            mealRiseConfirmed = mealRiseConfirmed,
        )
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = adjPred,
            eventualTerminal = adjEventual,
        )
        return SafetyPredictionTerminals(
            predBg = adjPred,
            eventualBg = adjEventual,
            uamTerminalMgdl = uamTerminal,
            mealRiseConfirmed = mealRiseConfirmed,
            compositeMinMgdl = composite,
        )
    }

    /**
     * Preferred path when [ScenarioProjectionPair] is available — floor for safety, best for meal-rise uplift.
     */
    fun resolveFromScenario(
        bg: Double,
        delta: Float,
        mealContext: MealSafetyContext,
        projection: ScenarioProjectionPair,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
    ): SafetyPredictionTerminals {
        val floor = projection.clinicalFloor
        val best = projection.scenarioBest
        val scenarioCtx = ScenarioProjectionContext(mealContext = mealContext)
        val mealRiseConfirmed = isMealRiseConfirmed(bg, delta, mealContext, mealAbsorptionPhase) ||
            ScenarioProjectionEngine.isMealRiseConfirmed(bg = bg, delta = delta, ctx = scenarioCtx)
        val floorPred = minOf(floor.pathMinMgdl, floor.terminalMgdl)
        val (adjPred, adjEventual) = adjustTerminals(
            bg = bg,
            delta = delta,
            pred = floorPred,
            eventual = floor.terminalMgdl,
            uamTerminal = best.terminalMgdl,
            mealRiseConfirmed = mealRiseConfirmed,
        )
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = adjPred,
            eventualTerminal = adjEventual,
        )
        return SafetyPredictionTerminals(
            predBg = adjPred,
            eventualBg = adjEventual,
            uamTerminalMgdl = best.terminalMgdl,
            mealRiseConfirmed = mealRiseConfirmed,
            compositeMinMgdl = composite,
        )
    }

    internal fun isMealRiseConfirmed(
        bg: Double,
        delta: Float,
        mealContext: MealSafetyContext,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
    ): Boolean =
        mealAbsorptionPhase.isActive ||
            mealContext.hasMealIntent ||
            (delta >= PredictiveHypoConstants.RISING_MODERATE_DELTA.toFloat() && bg >= 90.0)

    internal fun adjustTerminals(
        bg: Double,
        delta: Float,
        pred: Double,
        eventual: Double,
        uamTerminal: Double?,
        mealRiseConfirmed: Boolean,
    ): Pair<Double, Double> {
        if (!mealRiseConfirmed || uamTerminal == null || !uamTerminal.isFinite()) {
            return pred to eventual
        }
        var adjPred = pred
        var adjEventual = eventual
        val floorArtefact = eventual <= AimiRiskConstants.NUMERIC_FLOOR_MGDL + 1.0
        if (floorArtefact && uamTerminal > eventual) {
            adjEventual = uamTerminal
        } else if (uamTerminal > eventual + 15.0 && bg > eventual + 20.0) {
            adjEventual = uamTerminal
        }
        if (adjPred < bg - 40.0 && uamTerminal > adjPred && delta >= 0f) {
            val upliftCap = bg + delta.toDouble() * 8.0
            adjPred = maxOf(adjPred, minOf(uamTerminal, upliftCap))
        }
        return adjPred to adjEventual
    }

    /**
     * Meal-aware terminal adjustment for the DECISION risk envelope (SMB hypo-gate composite).
     * Reuses [adjustTerminals] so the DECISION composite stays consistent with the safety-start path.
     * Skips uplift when [predictionAuthority] flags false-meal suppression.
     */
    fun adjustForDecisionEnvelope(
        bg: Double,
        delta: Float,
        predForDecision: Double,
        eventualForDecision: Double,
        predictionAuthority: DecisionPredictionAuthority?,
        mealContext: MealSafetyContext,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
    ): Pair<Double, Double> {
        val mealRiseConfirmed =
            predictionAuthority?.falseMealSuppression != true &&
                isMealRiseConfirmed(bg, delta, mealContext, mealAbsorptionPhase)
        return adjustTerminals(
            bg = bg,
            delta = delta,
            pred = predForDecision,
            eventual = eventualForDecision,
            uamTerminal = predictionAuthority?.scenarioBestTerminalMgdl,
            mealRiseConfirmed = mealRiseConfirmed,
        )
    }
}
