package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot
import org.json.JSONArray
import org.json.JSONObject

internal object RecursiveBeliefAuthorityGate {

    private const val SENSOR_BLOCK_THRESHOLD = 0.45
    private const val SENSOR_SOFT_THRESHOLD = 0.65
    private const val POST_HYPO_BLOCK_THRESHOLD = 0.82
    private const val POST_HYPO_SOFT_THRESHOLD = 0.60
    private const val RESISTANCE_SOFT_THRESHOLD = 0.78
    private const val MEAL_BYPASS_MODE_CONFIDENCE = 0.72
    private const val MEAL_BYPASS_LATENT_CONFIDENCE = 0.70
    private const val MEAL_BYPASS_CAUSAL_CONFIDENCE = 0.68
    private const val MEAL_BYPASS_MEAL_MARGIN = 0.10
    private const val MEAL_BYPASS_PROTECTION_CONFIDENCE = 0.68

    data class Input(
        val authorityEnabled: Boolean,
        val requestedAuthority: ReleaseAuthority,
        val predictionAvailable: Boolean,
        val phaseOutput: PhysiologicalPhaseClassifier.Output?,
        val patternSnapshot: PhysiologicalPatternSnapshot?,
        val latentState: PhysioLatentState?,
        val hypothesisState: UamHypothesisState?,
        val patientState: PatientStateSnapshot?,
        val patientModeDecision: PatientModeOrchestrator.Decision?,
        val safetyRiskExport: SafetyRiskExportSnapshot?,
        val chaos: RbtChaosEvaluator.Result? = null,
        val episode: RbtEpisodeMemory.EpisodeState? = null,
        /** Current BG (mg/dL) for post-hypo aggressive-rise exit. */
        val bgMgdl: Double? = null,
        /** Profile / effective target (mg/dL). */
        val targetBgMgdl: Double? = null,
        /** 5‑minute delta (mg/dL). */
        val deltaMgdl5m: Double? = null,
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
        val patientModeDecision = input.patientModeDecision
        val aggressiveRiseExit = PostHypoAggressiveRiseExit.shouldExit(
            bgMgdl = input.bgMgdl ?: Double.NaN,
            targetBgMgdl = input.targetBgMgdl ?: Double.NaN,
            deltaMgdl5m = input.deltaMgdl5m ?: Double.NaN,
        )
        val predictiveHypoMealBypass = shouldBypassPredictiveHypoForMeal(input, aggressiveRiseExit)
        val hyperReleaseSuppressed =
            input.patternSnapshot?.suppressHyperRelease == true ||
                input.phaseOutput?.policy?.capsHtrRelease() == true
        if (aggressiveRiseExit) {
            reasonCodes += PostHypoAggressiveRiseExit.REASON_CODE
        }

        if (!input.predictionAvailable) {
            reasonCodes += "PRED_MISSING"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }
        if (input.chaos?.active == true) {
            reasonCodes += "CHAOS_BLOCK"
            maxAllowedAuthority = ReleaseAuthority.NONE
        } else if (input.chaos?.caution == true && maxAllowedAuthority == ReleaseAuthority.HARD) {
            reasonCodes += "CHAOS_CAUTION"
            maxAllowedAuthority = ReleaseAuthority.SOFT
        }
        if (input.episode?.kind == RbtEpisodeMemory.EpisodeKind.CHAOTIC) {
            reasonCodes += "EPISODE_CHAOTIC"
            if (maxAllowedAuthority == ReleaseAuthority.HARD) {
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
        }
        // Post-hypo episode soft-cap skipped on aggressive rise exit (act normally).
        if (input.episode?.kind == RbtEpisodeMemory.EpisodeKind.POST_HYPO_REBOUND && !aggressiveRiseExit) {
            reasonCodes += "EPISODE_POST_HYPO"
            if (maxAllowedAuthority == ReleaseAuthority.HARD) {
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
        }
        if (input.safetyRiskExport?.predictiveHypoSuppressed == true) {
            when {
                predictiveHypoMealBypass -> {
                    reasonCodes += "PREDICTIVE_HYPO_MEAL_BYPASS"
                    if (maxAllowedAuthority == ReleaseAuthority.HARD) {
                        maxAllowedAuthority = ReleaseAuthority.SOFT
                    }
                }
                // Aggressive post-hypo rise: keep SOFT release (not shadow NONE) so meal/HTR can act
                // before meal-bypass confirmation catches up (typically 1–2 ticks later).
                aggressiveRiseExit -> {
                    reasonCodes += "PREDICTIVE_HYPO_AGGRESSIVE_RISE"
                    if (maxAllowedAuthority == ReleaseAuthority.HARD) {
                        maxAllowedAuthority = ReleaseAuthority.SOFT
                    }
                }
                else -> {
                    reasonCodes += "PREDICTIVE_HYPO"
                    maxAllowedAuthority = ReleaseAuthority.NONE
                }
            }
        }
        if (hyperReleaseSuppressed) {
            reasonCodes += "PHYSIO_CAP"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }
        if (sensorConfidence < SENSOR_BLOCK_THRESHOLD) {
            reasonCodes += "SENSOR_LOW"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }
        if (postHypoProb >= POST_HYPO_BLOCK_THRESHOLD && mealProb < 0.40 && !aggressiveRiseExit) {
            reasonCodes += "POST_HYPO_BLOCK"
            maxAllowedAuthority = ReleaseAuthority.NONE
        }

        if (maxAllowedAuthority != ReleaseAuthority.NONE) {
            if (input.hypothesisState?.suppressMealInterpretation == true || latentState?.falseMealSuppression == true) {
                reasonCodes += "MEAL_SUPPRESS"
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
            if (hasDominantNonMealHypothesis(input.hypothesisState) && !aggressiveRiseExit) {
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
            if (postHypoProb >= POST_HYPO_SOFT_THRESHOLD && mealProb < 0.50 && !aggressiveRiseExit) {
                reasonCodes += "POST_HYPO_CAUTION"
                maxAllowedAuthority = ReleaseAuthority.SOFT
            }
            if ((patientModeDecision?.confidence ?: 0.0) >= 0.60) {
                when (patientModeDecision?.mode) {
                    PatientMode.POST_HYPO_RECOVERY -> {
                        if (aggressiveRiseExit) {
                            // Keep authority; do not veto to NONE during aggressive rise.
                            reasonCodes += "MODE_POST_HYPO_RECOVERY_BYPASSED"
                        } else {
                            reasonCodes += "MODE_POST_HYPO_RECOVERY"
                            maxAllowedAuthority = ReleaseAuthority.NONE
                        }
                    }
                    PatientMode.DAWN_ENDOGENOUS -> {
                        reasonCodes += "MODE_DAWN_ENDOGENOUS"
                        maxAllowedAuthority = ReleaseAuthority.SOFT
                    }
                    PatientMode.EXERCISE_AFTERBURN -> {
                        reasonCodes += "MODE_EXERCISE_AFTERBURN"
                        maxAllowedAuthority = ReleaseAuthority.SOFT
                    }
                    PatientMode.STRESS_RESISTANCE -> {
                        reasonCodes += "MODE_STRESS_RESISTANCE"
                        maxAllowedAuthority = ReleaseAuthority.SOFT
                    }
                    PatientMode.POOR_SLEEP_DAY -> {
                        reasonCodes += "MODE_POOR_SLEEP_DAY"
                        maxAllowedAuthority = ReleaseAuthority.SOFT
                    }
                    PatientMode.ABSORPTION_UNCERTAIN -> {
                        reasonCodes += "MODE_ABSORPTION_UNCERTAIN"
                        maxAllowedAuthority = ReleaseAuthority.SOFT
                    }
                    else -> Unit
                }
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
        val patientModeDecision = input.patientModeDecision
        if (patientModeDecision != null) {
            score -= patientModeDecision.protectionBias.coerceIn(0.0, 1.0) * 0.10
            if (hasProtectivePatientMode(patientModeDecision.mode)) {
                score -= patientModeDecision.confidence.coerceIn(0.0, 1.0) * 0.08
            }
        }
        if (input.patientState?.userIntent?.hasAnyIntent() == true && patientModeDecision?.mode != PatientMode.FAST_MEAL) {
            score -= input.patientState.userIntent.avgConfidence.coerceIn(0.0, 1.0) * 0.05
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

    private fun shouldBypassPredictiveHypoForMeal(
        input: Input,
        aggressiveRiseExit: Boolean,
    ): Boolean {
        val safety = input.safetyRiskExport ?: return false
        if (!safety.predictiveHypoSuppressed || !safety.mealContextActive || !safety.mealRiseConfirmed) {
            return false
        }
        if (input.patternSnapshot?.suppressMealInterpretation == true) return false
        if (input.hypothesisState?.suppressMealInterpretation == true) return false

        val latentState = input.latentState
        if (latentState?.falseMealSuppression == true) return false
        // Sticky post-hypo latent must not block meal-rise bypass once the aggressive-rise exit fires.
        if (!aggressiveRiseExit &&
            (latentState?.postHypoReboundProb ?: 0.0) >= POST_HYPO_SOFT_THRESHOLD
        ) {
            return false
        }

        val patientModeDecision = input.patientModeDecision
        if (!aggressiveRiseExit &&
            patientModeDecision != null &&
            hasProtectivePatientMode(patientModeDecision.mode) &&
            patientModeDecision.mode != PatientMode.FAST_MEAL &&
            patientModeDecision.confidence >= MEAL_BYPASS_PROTECTION_CONFIDENCE
        ) {
            return false
        }

        val modeMeal = patientModeDecision?.mode == PatientMode.FAST_MEAL &&
            (
                patientModeDecision.confidence >= MEAL_BYPASS_MODE_CONFIDENCE ||
                    patientModeDecision.mealBias >= 0.84
                )
        val causalMeal = input.patientState?.causalPosterior?.supportsMealInterpretation(
            minConfidence = MEAL_BYPASS_CAUSAL_CONFIDENCE,
            mealMargin = MEAL_BYPASS_MEAL_MARGIN,
        ) == true
        val latentMeal = (latentState?.mealProb ?: 0.0) >= MEAL_BYPASS_LATENT_CONFIDENCE
        val hypothesisMeal = (input.hypothesisState?.mealCompatibleProb() ?: 0.0) >= MEAL_BYPASS_LATENT_CONFIDENCE
        return modeMeal || causalMeal || latentMeal || hypothesisMeal
    }

    private fun hasProtectivePatientMode(mode: PatientMode): Boolean =
        when (mode) {
            PatientMode.DAWN_ENDOGENOUS,
            PatientMode.POST_HYPO_RECOVERY,
            PatientMode.STRESS_RESISTANCE,
            PatientMode.EXERCISE_AFTERBURN,
            PatientMode.POOR_SLEEP_DAY,
            PatientMode.ABSORPTION_UNCERTAIN,
            -> true
            else -> false
        }
}
