package app.aaps.plugins.aps.openAPSAIMI.patient

import java.util.Locale
import kotlin.math.roundToInt

data class PatientStatePresentation(
    val updatedSummary: String,
    val modeHeadline: String,
    val narrative: String,
    val physiologySummary: String,
    val intentSummary: String,
    val signalSummary: String,
    val deliverySummary: String,
    val reasonSummary: String,
)

internal object PatientStatePresentationBuilder {

    fun build(snapshot: PatientRuntimeSnapshot, nowMs: Long): PatientStatePresentation {
        val patientState = snapshot.patientState
        val patientMode = snapshot.patientModeDecision
        val modeLabel = humanize(patientMode.mode.name)
        val strategyLabel = humanize(patientMode.strategyHint.name)
        return PatientStatePresentation(
            updatedSummary = buildUpdatedSummary(snapshot.updatedAtMs, nowMs),
            modeHeadline = "$modeLabel (${percent(patientMode.confidence)})",
            narrative = buildNarrative(patientMode),
            physiologySummary = buildPhysiologySummary(patientState),
            intentSummary = buildIntentSummary(patientState),
            signalSummary = buildSignalSummary(patientState),
            deliverySummary = "$strategyLabel · meal bias ${percent(patientMode.mealBias)} · protection ${percent(patientMode.protectionBias)}",
            reasonSummary = patientMode.reasonCodes.joinToString(", ") { humanizeReason(it) },
        )
    }

    private fun buildUpdatedSummary(updatedAtMs: Long, nowMs: Long): String {
        val ageMinutes = ((nowMs - updatedAtMs).coerceAtLeast(0L)) / 60_000L
        return when {
            ageMinutes <= 0L -> "Updated just now"
            ageMinutes == 1L -> "Updated 1 minute ago"
            ageMinutes < 60L -> "Updated ${ageMinutes} minutes ago"
            else -> {
                val hours = ageMinutes / 60L
                val minutes = ageMinutes % 60L
                if (minutes == 0L) {
                    "Updated ${hours}h ago"
                } else {
                    "Updated ${hours}h ${minutes}m ago"
                }
            }
        }
    }

    private fun buildNarrative(decision: PatientModeOrchestrator.Decision): String =
        when (decision.strategyHint) {
            PatientStrategyHint.BASELINE_BALANCE ->
                "AIMI sees a balanced baseline and keeps a steady insulin posture."
            PatientStrategyHint.SMB_PRIORITY ->
                "AIMI sees a fast meal-like rise and keeps SMB support available."
            PatientStrategyHint.MEAL_SUPPORT ->
                "AIMI sees a prolonged meal pattern and favors sustained meal support."
            PatientStrategyHint.BASAL_BRIDGE ->
                "AIMI sees more endogenous drive than meal evidence and favors basal bridging."
            PatientStrategyHint.CONSERVATIVE_OBSERVE ->
                "AIMI sees a protective context and limits escalation while reassessing the body state."
            PatientStrategyHint.HYPO_RECOVERY ->
                "AIMI is protecting recovery after a low or alcohol-related rebound risk."
            PatientStrategyHint.PKPD_REASSESS ->
                "AIMI has low absorption confidence and reassesses PKPD before escalating."
        }

    private fun buildPhysiologySummary(state: PatientStateSnapshot): String =
        "Phase ${humanize(state.phase.name)} · Absorption ${humanize(state.mealAbsorptionPhase.name)} · UAM ${humanize(state.uamDominant.name)}"

    private fun buildIntentSummary(state: PatientStateSnapshot): String {
        if (!state.userIntent.hasAnyIntent()) {
            return "No active user intent"
        }
        val countLabel = if (state.userIntent.intentCount == 1) "context" else "contexts"
        return "Dominant ${humanize(state.userIntent.dominantIntent)} · ${percent(state.userIntent.avgConfidence)} confidence across ${state.userIntent.intentCount} ${countLabel}"
    }

    private fun buildSignalSummary(state: PatientStateSnapshot): String =
        "Meal ${percent(state.mealProb)} · Endogenous ${percent(state.endogenousGlucoseDrive)} · Resistance ${percent(state.transientResistanceProb)} · Sensor ${percent(state.sensorConfidence)}"

    private fun humanize(value: String): String =
        value.lowercase(Locale.US)
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar { char -> char.titlecase(Locale.US) } }

    private fun humanizeReason(code: String): String =
        when (code) {
            "LATENT_POST_HYPO" -> "Latent post-hypo rebound"
            "UAM_POST_HYPO" -> "UAM post-hypo signal"
            "CTX_ALCOHOL" -> "Alcohol context"
            "MEAL_FIRST_WAVE" -> "Meal first wave"
            "MEAL_PEAK_CORRECTION" -> "Meal peak correction"
            "UAM_MEAL" -> "UAM meal hypothesis"
            "CTX_MEAL_RISK" -> "Meal-risk context"
            "MEAL_EXTENDED_PHASE" -> "Extended meal phase"
            "UAM_LATE_FAT" -> "Late-fat meal hypothesis"
            "LATENT_ENDOGENOUS" -> "Endogenous glucose drive"
            "FALSE_MEAL_SUPPRESS" -> "False meal suppression"
            "CTX_ACTIVITY" -> "Activity context"
            "LATENT_RESISTANCE" -> "Transient resistance"
            "CTX_STRESS" -> "Stress context"
            "CTX_ILLNESS" -> "Illness context"
            "UAM_STRESS" -> "UAM stress hypothesis"
            "LATENT_SLEEP_DEBT" -> "Sleep debt"
            "SENSOR_LOW" -> "Low sensor confidence"
            "BASELINE" -> "Balanced baseline"
            else -> humanize(code)
        }

    private fun percent(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100.0).roundToInt()}%"
}
