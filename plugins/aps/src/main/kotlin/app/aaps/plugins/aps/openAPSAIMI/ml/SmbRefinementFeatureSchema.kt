package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState

internal object SmbRefinementFeatureSchema {

    const val BASE_FEATURE_COUNT = 10
    private const val LATENT_FEATURE_COUNT = 4
    private const val MODE_FEATURE_COUNT = 3
    const val INPUT_SIZE = BASE_FEATURE_COUNT + LATENT_FEATURE_COUNT + MODE_FEATURE_COUNT + 1

    private const val NEUTRAL_MEAL_PROB = 0f
    private const val NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE = 0f
    private const val NEUTRAL_CIRCADIAN_SI_FACTOR = 1f
    private const val NEUTRAL_TRANSIENT_RESISTANCE_PROB = 0f
    private const val NEUTRAL_PATIENT_MODE_MEAL_BIAS = 0.45f
    private const val NEUTRAL_PATIENT_MODE_PROTECTION_BIAS = 0.22f
    private const val NEUTRAL_CONTEXT_INTENT_CONFIDENCE = 0f

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

    val modeFeatureNames: List<String> = listOf(
        "patientModeMealBias",
        "patientModeProtectionBias",
        "contextIntentConfidence",
    )

    val csvFeatureNames: List<String> = requiredTrainingFeatureNames + latentFeatureNames + modeFeatureNames

    fun buildRuntimeFeatures(
        baseFeatures: FloatArray,
        trendIndicator: Float,
        physioLatentState: PhysioLatentState?,
        patientModeDecision: PatientModeOrchestrator.Decision?,
    ): FloatArray {
        require(baseFeatures.size == BASE_FEATURE_COUNT) {
            "Expected $BASE_FEATURE_COUNT base features, got ${baseFeatures.size}"
        }

        val latentFeatures = latentFeatureValues(physioLatentState)
        val modeFeatures = modeFeatureValues(patientModeDecision)
        return FloatArray(INPUT_SIZE).also { out ->
            baseFeatures.copyInto(out, endIndex = baseFeatures.size)
            latentFeatures.copyInto(out, destinationOffset = baseFeatures.size)
            modeFeatures.copyInto(out, destinationOffset = baseFeatures.size + latentFeatures.size)
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
        val modeFeatures = modeFeatureNames.map { name ->
            val index = headers.indexOf(name)
            cols.getOrNull(index)?.toFloatOrNull() ?: neutralValueFor(name)
        }

        return (requiredFeatures + latentFeatures + modeFeatures).toFloatArray()
    }

    fun latentFeatureValues(physioLatentState: PhysioLatentState?): FloatArray =
        floatArrayOf(
            physioLatentState?.mealProb?.toFloat() ?: NEUTRAL_MEAL_PROB,
            physioLatentState?.endogenousGlucoseDrive?.toFloat() ?: NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE,
            physioLatentState?.circadianSiFactor?.toFloat() ?: NEUTRAL_CIRCADIAN_SI_FACTOR,
            physioLatentState?.transientResistanceProb?.toFloat() ?: NEUTRAL_TRANSIENT_RESISTANCE_PROB,
        )

    fun modeFeatureValues(patientModeDecision: PatientModeOrchestrator.Decision?): FloatArray =
        floatArrayOf(
            patientModeDecision?.mealBias?.toFloat() ?: NEUTRAL_PATIENT_MODE_MEAL_BIAS,
            patientModeDecision?.protectionBias?.toFloat() ?: NEUTRAL_PATIENT_MODE_PROTECTION_BIAS,
            patientModeDecision?.userIntentConfidence?.toFloat() ?: NEUTRAL_CONTEXT_INTENT_CONFIDENCE,
        )

    private fun neutralValueFor(name: String): Float =
        when (name) {
            "mealProb" -> NEUTRAL_MEAL_PROB
            "endogenousGlucoseDrive" -> NEUTRAL_ENDOGENOUS_GLUCOSE_DRIVE
            "circadianSiFactor" -> NEUTRAL_CIRCADIAN_SI_FACTOR
            "transientResistanceProb" -> NEUTRAL_TRANSIENT_RESISTANCE_PROB
            "patientModeMealBias" -> NEUTRAL_PATIENT_MODE_MEAL_BIAS
            "patientModeProtectionBias" -> NEUTRAL_PATIENT_MODE_PROTECTION_BIAS
            "contextIntentConfidence" -> NEUTRAL_CONTEXT_INTENT_CONFIDENCE
            else -> 0f
        }
}
