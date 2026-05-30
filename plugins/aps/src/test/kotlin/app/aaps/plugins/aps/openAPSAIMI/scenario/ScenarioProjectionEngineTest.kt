package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioProjectionEngineTest {

    private fun flatCurves(bg: Double, terminal: Double): AdvancedPredictionCurves {
        val series = listOf(bg, terminal)
        return AdvancedPredictionCurves(
            iob = series,
            cob = series,
            uam = listOf(bg, bg + 20.0),
            zt = series,
            hybrid = listOf(bg, 39.0),
        )
    }

    @Test
    fun thomasLunchScenario_bestRisesAboveFloor() {
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 119.0,
                deltaMgdlPer5 = 0.6f,
                curves = flatCurves(119.0, 39.0),
                context = ScenarioProjectionContext(
                    mealContext = MealSafetyContext(mealModeActive = true),
                    effectiveCobG = 0.0,
                ),
            ),
        )
        assertTrue(projection.scenarioBest.terminalMgdl > projection.clinicalFloor.terminalMgdl)
        assertTrue(projection.scenarioBest.terminalMgdl >= 119.0)
        assertTrue(projection.contributors.any { it.id == ScenarioContributorId.MEAL_CONTEXT })
    }

    @Test
    fun openDivergingTrajectory_addsRiseContributor() {
        val analysis = TrajectoryAnalysis(
            classification = TrajectoryType.OPEN_DIVERGING,
            metrics = TrajectoryMetrics(
                curvature = 0.1,
                convergenceVelocity = -0.5,
                coherence = 0.5,
                energyBalance = 1.0,
                openness = 0.8,
            ),
            modulation = TrajectoryModulation.NEUTRAL,
            warnings = emptyList(),
            stableOrbitDistance = 10.0,
            predictedConvergenceTime = null,
        )
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 145.0,
                deltaMgdlPer5 = 3.5f,
                curves = flatCurves(145.0, 120.0),
                context = ScenarioProjectionContext(
                    trajectoryAnalysis = analysis,
                    trajectoryRelevanceScore = 0.6,
                ),
            ),
        )
        assertTrue(projection.contributors.any { it.id == ScenarioContributorId.TRAJECTORY_RISE })
        assertTrue(projection.scenarioBest.terminalMgdl >= projection.clinicalFloor.terminalMgdl)
    }

    @Test
    fun activityProtection_capsRiseProjection() {
        val before = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 130.0,
                deltaMgdlPer5 = 2.5f,
                curves = flatCurves(130.0, 180.0),
                context = ScenarioProjectionContext(),
            ),
        )
        val after = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 130.0,
                deltaMgdlPer5 = 2.5f,
                curves = flatCurves(130.0, 180.0),
                context = ScenarioProjectionContext(activityProtectionMode = true),
            ),
        )
        assertTrue(after.scenarioBest.terminalMgdl <= before.scenarioBest.terminalMgdl)
        assertTrue(after.contributors.any { it.id == ScenarioContributorId.ACTIVITY_PROTECTION })
    }

    @Test
    fun graphMapping_floorOnIobBestOnUam() {
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 100.0,
                deltaMgdlPer5 = 0f,
                curves = flatCurves(100.0, 80.0),
                context = ScenarioProjectionContext(),
            ),
        )
        assertEquals(projection.clinicalFloor.pointsMgdl, projection.clinicalFloor.pointsMgdl)
        assertEquals(projection.scenarioBest.pointsMgdl.size, projection.clinicalFloor.pointsMgdl.size)
    }
}
