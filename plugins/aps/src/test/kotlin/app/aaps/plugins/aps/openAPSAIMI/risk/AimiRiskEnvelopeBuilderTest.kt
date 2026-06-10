package app.aaps.plugins.aps.openAPSAIMI.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AimiRiskEnvelopeBuilderTest {

    @Test
    fun decisionEnvelopeUsesPostPkpdCompositeAndHypoThreshold() {
        val consensus = IobConsensus.resolve(aapsIobUnits = -0.2, pkpdIobUnits = 0.5)
        val envelope = AimiRiskEnvelopeBuilder.buildDecision(
            bg = 118.0,
            delta = -1f,
            predTerminal = 125.0,
            eventualTerminal = 125.0,
            pathBounds = PredictionPathBounds(
                pathMinRawMgdl = 35.0,
                pathMinClampedMgdl = 39.0,
                pathMinHitNumericFloor = true,
            ),
            aapsIobUnits = -0.2,
            iobConsensus = consensus,
            lgsThreshold = 70,
            naiveEbgSignGuardApplied = false,
        )
        assertEquals(AimiRiskPhase.DECISION, envelope.phase)
        assertEquals(118.0, envelope.compositeMinMgdl, 0.001)
        assertTrue(envelope.pathMinHitNumericFloor)
        assertEquals(0.5, envelope.iobDecisionUnits, 0.001)
    }

    @Test
    fun earlyEnvelopeMatchesSanityTerminals() {
        val envelope = AimiRiskEnvelopeBuilder.buildEarly(
            bg = 120.0,
            delta = 0f,
            predTerminal = 110.0,
            eventualTerminal = 115.0,
            predBGs = null,
            lgsThreshold = 70,
        )
        assertEquals(AimiRiskPhase.EARLY, envelope.phase)
        assertEquals(110.0, envelope.compositeMinMgdl, 0.001)
        assertEquals(115.0, envelope.eventualTerminalMgdl, 0.001)
    }
}
