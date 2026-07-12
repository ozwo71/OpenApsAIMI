package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionApplicator
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import kotlin.math.abs

/**
 * Phase C1 — applies [DecisionPredictionAuthority] to loop runtime state after resolve.
 * Shadow mode logs deltas without mutating dose-facing fields.
 */
data class PredictionAuthorityApplyResult(
    val applied: Boolean,
    val shadowOnly: Boolean,
    val eventualMgdl: Double,
    val predTerminalMgdl: Double,
    val predBGsRemapped: Boolean,
    val shadowDeltaEventualMgdl: Double?,
    val shadowDeltaPredTerminalMgdl: Double?,
    val source: String,
    val reason: String,
)

object PredictionAuthorityApplier {

    fun fromAuthority(authority: DecisionPredictionAuthority): PredictionAuthorityView =
        PredictionAuthorityView(
            predTerminalMgdl = authority.predTerminalMgdl,
            eventualTerminalMgdl = authority.eventualTerminalMgdl,
            pkpdEventualMgdl = authority.pkpdEventualMgdl,
            scenarioFloorMgdl = authority.scenarioFloorTerminalMgdl,
            scenarioBestMgdl = authority.scenarioBestTerminalMgdl,
            source = authority.source.name,
            scenarioUpliftApplied = authority.scenarioUpliftApplied,
            falseMealSuppression = authority.falseMealSuppression,
            reason = authority.reason,
        )

    fun apply(
        rT: RT,
        authority: DecisionPredictionAuthority,
        scenarioProjection: ScenarioProjectionPair?,
        enabled: Boolean,
        shadowOnly: Boolean,
        pkpdEventualBeforeApply: Double,
        pkpdPredTerminalBeforeApply: Double,
    ): PredictionAuthorityApplyResult {
        val shadowDeltaEventual = authority.eventualTerminalMgdl - pkpdEventualBeforeApply
        val shadowDeltaPred = authority.predTerminalMgdl - pkpdPredTerminalBeforeApply
        if (!enabled) {
            return PredictionAuthorityApplyResult(
                applied = false,
                shadowOnly = shadowOnly,
                eventualMgdl = pkpdEventualBeforeApply,
                predTerminalMgdl = pkpdPredTerminalBeforeApply,
                predBGsRemapped = false,
                shadowDeltaEventualMgdl = if (shadowOnly) shadowDeltaEventual else null,
                shadowDeltaPredTerminalMgdl = if (shadowOnly) shadowDeltaPred else null,
                source = authority.source.name,
                reason = authority.reason,
            )
        }
        var predBGsRemapped = false
        if (scenarioProjection != null) {
            ScenarioProjectionApplicator.applyToRt(rT, scenarioProjection)
            predBGsRemapped = true
        }
        rT.eventualBG = authority.eventualTerminalMgdl
        return PredictionAuthorityApplyResult(
            applied = true,
            shadowOnly = false,
            eventualMgdl = authority.eventualTerminalMgdl,
            predTerminalMgdl = authority.predTerminalMgdl,
            predBGsRemapped = predBGsRemapped,
            shadowDeltaEventualMgdl = null,
            shadowDeltaPredTerminalMgdl = null,
            source = authority.source.name,
            reason = authority.reason,
        )
    }

    fun formatShadowLogLine(result: PredictionAuthorityApplyResult): String? {
        if (!result.shadowOnly) return null
        val ev = result.shadowDeltaEventualMgdl ?: return null
        val pred = result.shadowDeltaPredTerminalMgdl ?: 0.0
        if (abs(ev) < 0.5 && abs(pred) < 0.5) return null
        return "PRED_AUTH_SHADOW: Δev=${ev.toInt()} ΔpredT=${pred.toInt()} src=${result.source} (${result.reason})"
    }
}
