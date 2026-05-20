package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath

data class AimiRiskEnvelope(
    val phase: AimiRiskPhase,
    val bgNowMgdl: Double,
    val deltaMgdlPer5: Float,
    val predTerminalMgdl: Double,
    val eventualTerminalMgdl: Double,
    val pathMinRawMgdl: Double?,
    val pathMinClampedMgdl: Double?,
    val pathMinHitNumericFloor: Boolean,
    val compositeMinMgdl: Double,
    val hypoThresholdMgdl: Double,
    val aapsIobUnits: Double,
    val pkpdIobUnits: Double?,
    val iobDecisionUnits: Double,
    val iobSource: IobDecisionSource,
    val naiveEbgSignGuardApplied: Boolean = false,
)

object AimiRiskEnvelopeBuilder {

    fun buildEarly(
        bg: Double,
        delta: Float,
        predTerminal: Double,
        eventualTerminal: Double,
        predBGs: Predictions?,
        lgsThreshold: Int?,
    ): AimiRiskEnvelope {
        val path = PredictionPathMath.boundsFromPredictions(predBGs)
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = predTerminal,
            eventualTerminal = eventualTerminal,
        )
        val hypoTh = HypoThresholdMath.computeHypoThreshold(composite, lgsThreshold)
        return AimiRiskEnvelope(
            phase = AimiRiskPhase.EARLY,
            bgNowMgdl = bg,
            deltaMgdlPer5 = delta,
            predTerminalMgdl = predTerminal,
            eventualTerminalMgdl = eventualTerminal,
            pathMinRawMgdl = path.pathMinRawMgdl,
            pathMinClampedMgdl = path.pathMinClampedMgdl,
            pathMinHitNumericFloor = path.pathMinHitNumericFloor,
            compositeMinMgdl = composite,
            hypoThresholdMgdl = hypoTh,
            aapsIobUnits = Double.NaN,
            pkpdIobUnits = null,
            iobDecisionUnits = Double.NaN,
            iobSource = IobDecisionSource.AAPS_DEFAULT,
        )
    }

    fun buildDecision(
        bg: Double,
        delta: Float,
        predTerminal: Double,
        eventualTerminal: Double,
        pathBounds: PredictionPathBounds,
        aapsIobUnits: Double,
        iobConsensus: IobConsensusResult,
        lgsThreshold: Int?,
        naiveEbgSignGuardApplied: Boolean,
    ): AimiRiskEnvelope {
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = predTerminal,
            eventualTerminal = eventualTerminal,
        )
        val hypoTh = HypoThresholdMath.computeHypoThreshold(composite, lgsThreshold)
        return AimiRiskEnvelope(
            phase = AimiRiskPhase.DECISION,
            bgNowMgdl = bg,
            deltaMgdlPer5 = delta,
            predTerminalMgdl = predTerminal,
            eventualTerminalMgdl = eventualTerminal,
            pathMinRawMgdl = pathBounds.pathMinRawMgdl,
            pathMinClampedMgdl = pathBounds.pathMinClampedMgdl,
            pathMinHitNumericFloor = pathBounds.pathMinHitNumericFloor,
            compositeMinMgdl = composite,
            hypoThresholdMgdl = hypoTh,
            aapsIobUnits = aapsIobUnits,
            pkpdIobUnits = iobConsensus.pkpdIobUnits,
            iobDecisionUnits = iobConsensus.decisionIobUnits,
            iobSource = iobConsensus.source,
            naiveEbgSignGuardApplied = naiveEbgSignGuardApplied,
        )
    }

    fun formatLogLine(envelope: AimiRiskEnvelope): String {
        val tag = when (envelope.phase) {
            AimiRiskPhase.EARLY -> "RISK_EARLY"
            AimiRiskPhase.DECISION -> "RISK_DECISION"
        }
        val pathRaw = envelope.pathMinRawMgdl?.let { "%.0f".format(it) } ?: "n/a"
        val pathClamp = envelope.pathMinClampedMgdl?.let { "%.0f".format(it) } ?: "n/a"
        val iobPart =
            if (envelope.phase == AimiRiskPhase.DECISION) {
                " iob=${"%.2f".format(envelope.aapsIobUnits)}→${"%.2f".format(envelope.iobDecisionUnits)}" +
                    "(${envelope.iobSource.name})"
            } else {
                ""
            }
        val floorFlag = if (envelope.pathMinHitNumericFloor) " floorHit=1" else ""
        return "$tag: compositeMin=${envelope.compositeMinMgdl.toInt()} hypoTh=${envelope.hypoThresholdMgdl.toInt()} " +
            "predT=${envelope.predTerminalMgdl.toInt()} evT=${envelope.eventualTerminalMgdl.toInt()} " +
            "pathRaw=$pathRaw pathClamp=$pathClamp$floorFlag$iobPart"
    }
}
