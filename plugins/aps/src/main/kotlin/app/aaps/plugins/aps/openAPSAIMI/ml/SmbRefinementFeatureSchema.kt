package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState

internal object SmbRefinementFeatureSchema {

    const val BASE_FEATURE_COUNT = 10
    private const val LATENT_FEATURE_COUNT = 4
    const val INPUT_SIZE = BASE_FEATURE_COUNT + LATENT_FEATURE_COUNT + 1

    private const val NEUTRAL_MEAL_PROB = 0f
    private const val NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE = 0f
    private const val NEUTRAL_CIRCADIAN_SI_FACTOR = 1f
    private const val NEUTRAL_TRANSIENT_RESISTANCE_PROB = 0f

    val requiredTrainingFeatureNames: List<String> = listOf(
        "bg",
        "iob",
        "cob",
        "delta",
        "shortAvgDelta",
        "longAvgDelta",
        "tdd7DaysPerHour",
        "tdd2DaysPerHour",
        "tddPerHour",
        "tdd24HrsPerHour",
    )

    val latentFeatureNames: List<String> = listOf(
        "mealProb",
        "endogenousGlucoseDrive",
        "circadianSiFactor",
        "transientResistanceProb",
    )

    val csvFeatureNames: List<String> = requiredTrainingFeatureNames + latentFeatureNames

    fun buildRuntimeFeatures(
        baseFeatures: FloatArray,
        trendIndicator: Float,
        physioLatentState: PhysioLatentState?,
    ): FloatArray {
        require(baseFeatures.size == BASE_FEATURE_COUNT) {
            "Expected $BASE_FEATURE_COUNT base features, got ${baseFeatures.size}"
        }

        val latentFeatures = latentFeatureValues(physioLatentState)
        return FloatArray(INPUT_SIZE).also { out ->
            baseFeatures.copyInto(out, endIndex = baseFeatures.size)
            latentFeatures.copyInto(out, destinationOffset = baseFeatures.size)
            out[INPUT_SIZE - 1] = trendIndicator
        }
    }

    fun parseTrainingFeatures(headers: List<String>, cols: List<String>): FloatArray? {
        val requiredFeatures = requiredTrainingFeatureNames.map { name ->
            val index = headers.indexOf(name)
            if (index == -1) {
                return null
            }
            cols.getOrNull(index)?.toFloatOrNull() ?: return null
        }
        val latentFeatures = latentFeatureNames.map { name ->
            val index = headers.indexOf(name)
            cols.getOrNull(index)?.toFloatOrNull() ?: neutralValueFor(name)
        }

        return (requiredFeatures + latentFeatures).toFloatArray()
    }

    fun latentFeatureValues(physioLatentState: PhysioLatentState?): FloatArray =
        floatArrayOf(
            physioLatentState?.mealProb?.toFloat() ?: NEUTRAL_MEAL_PROB,
            physioLatentState?.endogenousGlucoseDrive?.toFloat() ?: NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE,
            physioLatentState?.circadianSiFactor?.toFloat() ?: NEUTRAL_CIRCADIAN_SI_FACTOR,
            physioLatentState?.transientResistanceProb?.toFloat() ?: NEUTRAL_TRANSIENT_RESISTANCE_PROB,
        )

    private fun neutralValueFor(name: String): Float =
        when (name) {
            "mealProb" -> NEUTRAL_MEAL_PROB
            "endogenousGlucoseDrive" -> NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE
            "circadianSiFactor" -> NEUTRAL_CIRCADIAN_SI_FACTOR
            "transientResistanceProb" -> NEUTRAL_TRANSIENT_RESISTANCE_PROB
            else -> 0f
        }
}
