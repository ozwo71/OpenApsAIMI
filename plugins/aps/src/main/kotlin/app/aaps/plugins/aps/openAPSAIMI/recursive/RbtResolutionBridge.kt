package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.min

/**
 * Maps RBT resolver outputs to pump-path consumers (SafetyNet, LGS, stacking, Autodrive gater).
 */
object RbtResolutionBridge {

    data class AppliedHints(
        val ignoreMinPredictedCurve: Boolean,
        val mealPriorityContext: Boolean,
        val suppressMealInterpretation: Boolean,
        val mealChannel: MealChannelHint?,
        val waitBiasMultiplier: Double,
        val chaosScore: Double,
        val episodeKind: RbtEpisodeMemory.EpisodeKind?,
        val summary: String,
    )

    fun apply(
        resolution: DoseChannelResolution?,
        effectiveAuthority: ReleaseAuthority?,
        chaos: RbtChaosEvaluator.Result?,
        episode: RbtEpisodeMemory.EpisodeState?,
        defaultMealPriority: Boolean,
    ): AppliedHints {
        if (resolution == null) {
            return AppliedHints(
                ignoreMinPredictedCurve = false,
                mealPriorityContext = defaultMealPriority,
                suppressMealInterpretation = false,
                mealChannel = null,
                waitBiasMultiplier = 1.0,
                chaosScore = chaos?.score ?: 0.0,
                episodeKind = episode?.kind,
                summary = "RBT inactive",
            )
        }

        val ignoreMinPred = shouldIgnoreMinPredictedCurve(resolution)
        val mealChannel = resolution.mealChannel
        val suppressMeal = mealChannel == MealChannelHint.SUPPRESS ||
            resolution.reasonCodes.any { it == "PATTERN_MEAL_SUPPRESS" || it == "UAM_MEAL_SUPPRESS" }
        val mealPriority = when (mealChannel) {
            MealChannelHint.PRIORITY -> !suppressMeal
            MealChannelHint.SUPPRESS -> false
            MealChannelHint.NORMAL -> defaultMealPriority && !suppressMeal
        }
        val waitMul = waitBiasMultiplier(resolution.waitBias)
        val chaosActive = chaos?.active == true
        val adjustedWaitMul = if (chaosActive) {
            min(waitMul, 0.65)
        } else {
            waitMul
        }

        val parts = buildList {
            add("hypo=${resolution.hypoGuardMode.name}")
            add("meal=${mealChannel.name}")
            add("wait=${"%.2f".format(resolution.waitBias)}→${"%.2f".format(adjustedWaitMul)}")
            if (ignoreMinPred) add("minPredIgnored")
            effectiveAuthority?.let { add("auth=${it.name}") }
            chaos?.let { add("chaos=${"%.2f".format(it.score)}") }
            episode?.let { add("episode=${it.kind.name}") }
        }

        return AppliedHints(
            ignoreMinPredictedCurve = ignoreMinPred,
            mealPriorityContext = mealPriority,
            suppressMealInterpretation = suppressMeal,
            mealChannel = mealChannel,
            waitBiasMultiplier = adjustedWaitMul,
            chaosScore = chaos?.score ?: 0.0,
            episodeKind = episode?.kind,
            summary = parts.joinToString(" "),
        )
    }

    fun shouldIgnoreMinPredictedCurve(resolution: DoseChannelResolution?): Boolean {
        if (resolution == null) return false
        return when (resolution.hypoGuardMode) {
            HypoGuardMode.IGNORE_MINPRED,
            HypoGuardMode.PARTIAL,
            -> true
            HypoGuardMode.FULL -> resolution.hypoMinPredIgnored
        }
    }

    fun waitBiasMultiplier(waitBias: Double): Double =
        (1.0 - (waitBias - 0.15).coerceAtLeast(0.0) * 0.75).coerceIn(0.55, 1.0)
}
