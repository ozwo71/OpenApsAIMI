package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot
import org.json.JSONArray
import org.json.JSONObject

internal object RecursiveBeliefAuthorityGate {

    private const val SENSOR_BLOCK_THRESHOLD = 0.45
    private const val SENSOR_SOFT_THRESHOLD = 0.65
    private const val POST_HYPO_BLOCK_THRESHOLD = 0.82
    private const val POST_HYPO_SOFT_THRESHOLD = 0.60
    private const val RESISTANCE_SOFT_THRESHOLD = 0.78

    data class Input(
        val authorityEnabled: Boolean,
        val requestedAuthority: ReleaseAuthority,
        val predictionAvailable: Boolean,
        val phaseOutput: PhysiologicalPhaseClassifier.Output?,
        val patternSnapshot: PhysiologicalPatternSnapshot?,
        val latentState: PhysioLatentState?,
        val hypothesisState: UamHypothesisState?,
        val safetyRiskExport: SafetyRiskExportSnapshot?,
    )

    data class Decision(
        val requestedAuthority: ReleaseAuthority,
        val maxAllowedAuthority: ReleaseAuthority,
        val effectiveAuthority: ReleaseAuthority,
        val readinessScore: Double,
        val liftBlend: Double,
        val reasonCodes: List<String>,
    ) {
        val shadowOnly: Boolean
            get() = effectiveAuthority == ReleaseAuthority.NONE

        val softLimited: Boolean
            get() = requestedAuthority == ReleaseAuthority.HARD && effectiveAuthority == ReleaseAuthority.SOFT

        fun toJsonObject(): JSONObject =
            JSONObject().apply {
                put("requested_authority", requestedAuthority.name)
                put("max_allowed_authority", maxAllowedAuthority.name)
                put("effective_authority", effectiveAuthority.name)
                put("readiness_score", readinessScore)
                put("lift_blend", liftBlend)
                put("shadow_only", shadowOnly)
                put("soft_limited", softLimited)
                put("reason_codes", JSONArray(reasonCodes))
            }

        fun summary(): String =
            "req=${requestedAuthority.name} eff=${effectiveAuthority.name} score=${"%.2f".format(readinessScore)} " +
                "blend=${"%.2f".format(liftBlend)} reasons=${reasonCodes.joinToString(",")}"
    }

    fun evaluate(input: Input): Decision {
        val readinessScore = buildReadinessScore(input)
        val requestedAuthority = input.requestedAuthority

        if (!input.authorityEnabled) {
            return blockedDecision(requestedAuthority, readinessScore, "PREF_OFF")
        }
        if (requestedAuthority == ReleaseAuthority.NONE) {
            return blockedDecision(requestedAuthority, readinessScore, "NO_RELEASE")
        }

        val reasonCodes = linkedSetOf<String>()
        var maxAllowedAuthority = ReleaseAuthority.HARD
        val latentState = input.latentState
        val sensorConfidence = latentState?.sensorConfidence ?: 0.0
        val mealProb = latentState?.mealProb ?: 0.0
        val postHypoProb = latentState?.postHypoReboundProb ?: 0.0
        val transientResistanceProb = latentState?.transientResistanceProb ?: 0.0
        val hyperReleaseSuppressed =
            input.patternSnapshot?.suppressHyperRelease == true ||
                input.phaseOutput?.policy?.capsHtrRelease() == true

        if (!input.predictionAvailable) {
            reasonCodes += "PRED_MISSING"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }
        if (input.safetyRiskExport?.predictiveHypoSuppressed == true) {
            reasonCodes += "PREDICTIVE_HYPO"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }
        if (hyperReleaseSuppressed) {
            reasonCodes += "PHYSIO_CAP"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }
        if (sensorConfidence < SENSOR_BLOCK_THRESHOLD) {
            reasonCodes += "SENSOR_LOW"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }
        if (postHypoProb >= POST_HYPO_BLOCK_THRESHOLD && mealProb < 0.40) {
            reasonCodes += "POST_HYPO_BLOCK"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }

        if (maxAllowedAuthority != ReleaseAuthority.NONE) {
            if (input.hypothesisState?.suppressMealInterpretation == true || latentState?.falseMealSuppression == true) {
                reasonCodes += "MEAL_SUPPRESS"
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
            if (hasDominantNonMealHypothesis(input.hypothesisState)) {
                reasonCodes += "NON_MEAL_DOM"
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
            if (sensorConfidence < SENSOR_SOFT_THRESHOLD) {
                reasonCodes += "SENSOR_CAUTION"
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
            if (transientResistanceProb >= RESISTANCE_SOFT_THRESHOLD && mealProb < 0.45) {
                reasonCodes += "RESISTANCE_CAUTION"
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
            if (postHypoProb >= POST_HYPO_SOFT_THRESHOLD && mealProb < 0.50) {
                reasonCodes += "POST_HYPO_CAUTION"
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
        }

        val effectiveAuthority = effectiveAuthority(
            requestedAuthority = requestedAuthority,
            maxAllowedAuthority = maxAllowedAuthority,
        )
        val reasons = if (reasonCodes.isEmpty()) listOf("READY") else reasonCodes.toList()
        return Decision(
            requestedAuthority = requestedAuthority,
            maxAllowedAuthority = maxAllowedAuthority,
            effectiveAuthority = effectiveAuthority,
            readinessScore = readinessScore,
            liftBlend = liftBlend(effectiveAuthority, readinessScore),
            reasonCodes = reasons,
        )
    }

    private fun blockedDecision(
        requestedAuthority: ReleaseAuthority,
        readinessScore: Double,
        reasonCode: String,
    ): Decision =
        Decision(
            requestedAuthority = requestedAuthority,
            maxAllowedAuthority = ReleaseAuthority.NONE,
            effectiveAuthority = ReleaseAuthority.NONE,
            readinessScore = readinessScore,
            liftBlend = 0.0,
            reasonCodes = listOf(reasonCode),
        )

    private fun effectiveAuthority(
        requestedAuthority: ReleaseAuthority,
        maxAllowedAuthority: ReleaseAuthority,
    ): ReleaseAuthority =
        when (requestedAuthority) {
            ReleaseAuthority.NONE -> ReleaseAuthority.NONE
            ReleaseAuthority.SOFT -> {
                if (maxAllowedAuthority == ReleaseAuthority.NONE) ReleaseAuthority.NONE else ReleaseAuthority.SOFT
            }
            ReleaseAuthority.HARD -> maxAllowedAuthority
        }

    private fun liftBlend(effectiveAuthority: ReleaseAuthority, readinessScore: Double): Double =
        when (effectiveAuthority) {
            ReleaseAuthority.NONE -> 0.0
            ReleaseAuthority.SOFT -> (0.55 + readinessScore * 0.20).coerceIn(0.55, 0.75)
            ReleaseAuthority.HARD -> 1.0
        }

    private fun buildReadinessScore(input: Input): Double {
        val latentState = input.latentState
        var score = 1.0

        if (!input.predictionAvailable) score -= 0.35
        if (input.safetyRiskExport?.predictiveHypoSuppressed == true) score -= 0.40
        if (input.patternSnapshot?.suppressHyperRelease == true || input.phaseOutput?.policy?.capsHtrRelease() == true) {
            score -= 0.35
        }

        val sensorConfidence = (latentState?.sensorConfidence ?: 0.0).coerceIn(0.0, 1.0)
        score -= (1.0 - sensorConfidence) * 0.30
        score -= (latentState?.postHypoReboundProb ?: 0.0).coerceIn(0.0, 1.0) * 0.15
        score -= (latentState?.transientResistanceProb ?: 0.0).coerceIn(0.0, 1.0) * 0.10

        if (latentState?.falseMealSuppression == true || input.hypothesisState?.suppressMealInterpretation == true) {
            score -= 0.15
        }
        if (hasDominantNonMealHypothesis(input.hypothesisState)) {
            score -= 0.10
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun hasDominantNonMealHypothesis(hypothesisState: UamHypothesisState?): Boolean {
        if (hypothesisState == null) return false
        if (hypothesisState.dominantConfidence < 0.65) return false
        return when (hypothesisState.dominant) {
            UamHypothesisId.DAWN_ENDOGENOUS,
            UamHypothesisId.STRESS,
            UamHypothesisId.POST_HYPO,
            -> true
            else -> false
        }
    }
}
