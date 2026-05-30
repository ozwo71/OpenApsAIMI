package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminalsResolver
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPredictionTerminalsResolverTest {

    @Test
    fun thomasPreSpike_replacesFloorEventualWithUamTerminal() {
        val result = SafetyPredictionTerminalsResolver.resolve(
            bg = 119.0,
            delta = 0.6f,
            sanityPred = 65.0,
            sanityEventual = 39.0,
            uamTerminal = 128.0,
            mealContext = MealSafetyContext(mealModeActive = true),
        )
        assertTrue(result.mealRiseConfirmed)
        assertEquals(128.0, result.eventualBg, 0.001)
        assertEquals(119.0, result.compositeMinMgdl, 0.001)
    }

    @Test
    fun noMealRise_keepsInsulinOnlyTerminals() {
        val result = SafetyPredictionTerminalsResolver.resolve(
            bg = 118.0,
            delta = -0.5f,
            sanityPred = 65.0,
            sanityEventual = 39.0,
            uamTerminal = 120.0,
            mealContext = MealSafetyContext(),
        )
        assertFalse(result.mealRiseConfirmed)
        assertEquals(39.0, result.eventualBg, 0.001)
        assertEquals(39.0, result.compositeMinMgdl, 0.001)
    }

    @Test
    fun risingDeltaWithoutMealContext_confirmsMealRise() {
        assertTrue(
            SafetyPredictionTerminalsResolver.isMealRiseConfirmed(
                bg = 137.0,
                delta = 5.0f,
                mealContext = MealSafetyContext(),
            ),
        )
    }

    @Test
    fun resolveFromScenario_usesFloorForSafetyAndBestForMealUplift() {
        val floor = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.CLINICAL_FLOOR,
            listOf(119.0, 39.0),
        )
        val best = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.SCENARIO_BEST,
            listOf(119.0, 155.0),
        )
        val projection = ScenarioProjectionPair(
            clinicalFloor = floor,
            scenarioBest = best,
            contributors = emptyList(),
            cobPointsMgdl = listOf(119, 130),
            ztPointsMgdl = listOf(119, 39),
        )
        val result = SafetyPredictionTerminalsResolver.resolveFromScenario(
            bg = 119.0,
            delta = 0.6f,
            mealContext = MealSafetyContext(mealModeActive = true),
            projection = projection,
        )
        assertEquals(119.0, result.compositeMinMgdl, 0.001)
        assertTrue(result.eventualBg >= 119.0)
    }
}
