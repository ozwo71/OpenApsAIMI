package app.aaps.plugins.aps.openAPSAIMI.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AimiRiskEnvelopeBuilderTest {

    @Test
    fun decisionEnvelopeUsesPostPkpdCompositeAndHypoThreshold() {
        val consensus = IobConsensus.resolve(aapsIobUnits = -0.2, pkpdIobUnits = 0.5)
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 96.0,
            eventualTerminalMgdl = 184.0,
            pkpdEventualMgdl = 125.0,
            scenarioFloorTerminalMgdl = 96.0,
            scenarioBestTerminalMgdl = 184.0,
            source = DecisionPredictionSource.SCENARIO_MEAL_UPLIFT,
            scenarioUpliftApplied = true,
            falseMealSuppression = false,
            reason = "test",
        )
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
            predictionAuthority = authority,
        )
        assertEquals(AimiRiskPhase.DECISION, envelope.phase)
        assertEquals(96.0, envelope.compositeMinMgdl, 0.001)
        assertTrue(envelope.pathMinHitNumericFloor)
        assertEquals(0.5, envelope.iobDecisionUnits, 0.001)
        assertEquals(DecisionPredictionSource.SCENARIO_MEAL_UPLIFT, envelope.decisionSource)
        assertEquals(184.0, envelope.eventualTerminalMgdl, 0.001)
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
