package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Cascade D4 / C1 — single dose-facing eventual + minPred for the tick.
 *
 * Built once after Prediction Authority apply (+ thin Clamp). SafetyNet, stacking, Tube
 * refresh and SMB gates must read this instead of raw PKPD / curve-min floors.
 */
data class DoseTerminalSnapshot(
    val eventualMgdl: Double,
    val minPredMgdl: Double,
    val source: String,
    val authorityApplied: Boolean,
    val clampReconciled: Boolean,
    val clampReason: String?,
    val predBGsRemapped: Boolean,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("eventual_mgdl", eventualMgdl)
            put("min_pred_mgdl", minPredMgdl)
            put("source", source)
            put("authority_applied", authorityApplied)
            put("clamp_reconciled", clampReconciled)
            clampReason?.let { put("clamp_reason", it) }
            put("pred_bgs_remapped", predBGsRemapped)
        }

    companion object {
        const val LOG_PREFIX = "DOSE_TERMINAL_SNAPSHOT"

        fun formatLogLine(snapshot: DoseTerminalSnapshot): String =
            "$LOG_PREFIX: ev=${snapshot.eventualMgdl.toInt()} " +
                "minPred=${snapshot.minPredMgdl.toInt()} " +
                "src=${snapshot.source} " +
                "auth=${snapshot.authorityApplied} " +
                "clamp=${snapshot.clampReconciled}" +
                (snapshot.clampReason?.let { "($it)" } ?: "") +
                " curves=${snapshot.predBGsRemapped}"
    }
}

object DoseTerminalSnapshotBuilder {

    /**
     * Compose Authority apply result with thin Clamp (D4.3: no-op when eventual already clear of
     * false PKPD floor; still lifts when Authority retained a low PKPD eventual).
     */
    fun build(
        authority: DecisionPredictionAuthority?,
        applyResult: PredictionAuthorityApplyResult?,
        authorityEnabled: Boolean,
        fallbackEventualMgdl: Double,
        fallbackMinPredMgdl: Double,
        clampInput: ClampPkpdScenarioReconcile.Input,
    ): DoseTerminalSnapshot {
        val authorityApplied = authorityEnabled && applyResult?.applied == true
        val baseEventual = when {
            authorityApplied -> applyResult!!.eventualMgdl
            authorityEnabled -> authority?.eventualTerminalMgdl?.takeIf { it.isFinite() }
                ?: fallbackEventualMgdl
            else -> fallbackEventualMgdl
        }
        val baseMinPred = when {
            authorityEnabled ->
                authority?.predTerminalMgdl?.takeIf { it.isFinite() }
                    ?: applyResult?.predTerminalMgdl?.takeIf { it.isFinite() }
                    ?: fallbackMinPredMgdl
            else -> fallbackMinPredMgdl
        }
        val clamp = ClampPkpdScenarioReconcile.reconcile(
            clampInput.copy(pkpdEventualMgdl = baseEventual),
        )
        val eventual = if (clamp.reconciled) max(baseEventual, clamp.eventualMgdl) else baseEventual
        // When eventual is released from a false PKPD floor, minPred must not stay at NUMERIC_FLOOR
        // or stacking/Tube still crush on path-min poison.
        val pkpdBaseline = authority?.pkpdEventualMgdl?.takeIf { it.isFinite() } ?: fallbackEventualMgdl
        val eventualLifted = eventual > pkpdBaseline + 0.5
        val safePathMin = clampInput.scenarioPathMinMgdl?.takeIf {
            it.isFinite() &&
                !clampInput.scenarioPathMinHitFloor &&
                it >= ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL
        }
        val minPred = when {
            (clamp.reconciled || (authorityApplied && eventualLifted)) && safePathMin != null ->
                min(max(baseMinPred, safePathMin), eventual)
            clamp.reconciled ->
                min(max(baseMinPred, ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL), eventual)
            else -> baseMinPred
        }
        val source = when {
            clamp.reconciled && authorityApplied -> "${applyResult!!.source}+CLAMP_${clamp.reason}"
            clamp.reconciled -> "CLAMP_${clamp.reason}"
            authorityApplied -> applyResult!!.source
            authorityEnabled -> authority?.source?.name ?: "PKPD_FALLBACK"
            else -> "PKPD_RAW"
        }
        return DoseTerminalSnapshot(
            eventualMgdl = eventual,
            minPredMgdl = minPred,
            source = source,
            authorityApplied = authorityApplied,
            clampReconciled = clamp.reconciled,
            clampReason = clamp.reason,
            predBGsRemapped = applyResult?.predBGsRemapped == true,
        )
    }
}
