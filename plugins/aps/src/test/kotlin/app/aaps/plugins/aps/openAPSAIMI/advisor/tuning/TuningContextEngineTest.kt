package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningContextEngineTest {

    private fun baseMetrics(
        timeBelow70: Double = 0.02,
        timeAbove180: Double = 0.30,
        tir: Double = 0.65,
    ) = AdvisorMetrics(
        periodLabel = "7d",
        tir70_180 = tir,
        tir70_140 = 0.50,
        timeBelow70 = timeBelow70,
        timeBelow54 = 0.01,
        timeAbove180 = timeAbove180,
        timeAbove250 = 0.05,
        meanBg = 155.0,
        gmi = 6.8,
        tdd = 42.0,
        basalPercent = 0.45,
        hypoEvents = 2,
        severeHypoEvents = 0,
        hyperEvents = 5,
        todayTir = null,
        todayTdd = null,
    )

    private fun mockPreferences(
        reliefEnabled: Boolean = true,
        tubeEnabled: Boolean = false,
    ): Preferences = mockk(relaxed = true) {
        every { get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled) } returns reliefEnabled
        every { get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled) } returns tubeEnabled
        every { get(DoubleKey.OApsAIMIHighBGMaxSMB) } returns 1.0
        every { get(DoubleKey.OApsAIMIMaxSMB) } returns 1.0
        every { get(DoubleKey.OApsAIMILunchFactor) } returns 50.0
        every { get(DoubleKey.OApsAIMIDinnerFactor) } returns 50.0
        every { get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor) } returns 0.65
        every { get(DoubleKey.OApsAIMIPriorityMaxIobFactor) } returns 1.0
        every { get(DoubleKey.OApsAIMIPriorityMaxIobExtraU) } returns 0.0
        every { get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold) } returns 0.65
        every { get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.85
        every { get(DoubleKey.AimiTubeAggressiveness) } returns 1.0
        every { get(DoubleKey.AimiTubeHypoFloorMgdl) } returns 72.0
    }

    @Test
    fun `meal rise strong tier increases high bg max smb`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.MEAL_RISE,
            baseMetrics(timeAbove180 = 0.42, timeBelow70 = 0.02),
            mockPreferences(),
            t3cBrittleMode = false,
        )
        assertEquals(TuningStepTier.STRONG, plan.dominantTier)
        assertTrue(plan.isActionable)
        val highBg = plan.changes.first { it.key == DoubleKey.OApsAIMIHighBGMaxSMB }
        assertEquals(1.0, highBg.oldValue)
        assertEquals(1.25, highBg.newValue)
    }

    @Test
    fun `meal rise blocked when hypos dominate`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.MEAL_RISE,
            baseMetrics(timeBelow70 = 0.08, timeAbove180 = 0.35),
            mockPreferences(),
            t3cBrittleMode = false,
        )
        assertFalse(plan.isActionable)
        assertTrue(plan.blockedReason!!.contains("blocked"))
    }

    @Test
    fun `meal rise with moderate hypos reduces lunch factor instead of tube`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.MEAL_RISE,
            baseMetrics(timeBelow70 = 0.05, timeAbove180 = 0.35),
            mockPreferences(),
            t3cBrittleMode = false,
        )
        assertTrue(plan.isActionable)
        assertFalse(plan.changes.any { it.key == BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled })
        assertTrue(plan.changes.any { it.key == DoubleKey.OApsAIMILunchFactor && (it.newValue as Double) < (it.oldValue as Double) })
    }

    @Test
    fun `hypo guard decreases max smb`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.HYPO_GUARD,
            baseMetrics(timeBelow70 = 0.07, timeAbove180 = 0.10),
            mockPreferences(),
            t3cBrittleMode = false,
        )
        assertTrue(plan.changes.any { it.key == DoubleKey.OApsAIMIMaxSMB && (it.newValue as Double) < (it.oldValue as Double) })
    }

    @Test
    fun `hypo guard strong disables tube and relief`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.HYPO_GUARD,
            baseMetrics(timeBelow70 = 0.07, timeAbove180 = 0.10),
            mockPreferences(reliefEnabled = true, tubeEnabled = true),
            t3cBrittleMode = false,
        )
        assertTrue(plan.changes.any {
            it.key == BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled && it.newValue == false
        })
        assertTrue(plan.changes.any {
            it.key == BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled && it.newValue == false
        })
    }

    @Test
    fun `hypo guard reduces lunch and dinner factors`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.HYPO_GUARD,
            baseMetrics(timeBelow70 = 0.05, timeAbove180 = 0.08),
            mockPreferences(),
            t3cBrittleMode = false,
        )
        assertTrue(plan.changes.any { it.key == DoubleKey.OApsAIMILunchFactor })
        assertTrue(plan.changes.any { it.key == DoubleKey.OApsAIMIDinnerFactor })
    }

    @Test
    fun `auto resolves to mixed when both burdens significant`() {
        val effective = TuningContextEngine.resolveAutoContext(
            baseMetrics(timeBelow70 = 0.05, timeAbove180 = 0.18),
        )
        assertEquals(AimiTuningContext.MIXED_BALANCE, effective)
    }

    @Test
    fun `mixed applies hypo reductions and relief enable without smb increase`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.AUTO_BALANCE,
            baseMetrics(timeBelow70 = 0.05, timeAbove180 = 0.18),
            mockPreferences(reliefEnabled = false),
            t3cBrittleMode = false,
        )
        assertEquals(AimiTuningContext.MIXED_BALANCE, plan.effectiveContext)
        assertTrue(plan.changes.any { it.key == DoubleKey.OApsAIMIMaxSMB && (it.newValue as Double) < (it.oldValue as Double) })
        assertTrue(plan.changes.any { it.key == BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled && it.newValue == true })
        assertFalse(plan.changes.any { it.key == DoubleKey.OApsAIMIMaxSMB && (it.newValue as Double) > (it.oldValue as Double) })
    }

    @Test
    fun `auto balance resolves to hypo guard when lows lead clearly`() {
        val effective = TuningContextEngine.resolveAutoContext(
            baseMetrics(timeBelow70 = 0.06, timeAbove180 = 0.15),
        )
        assertEquals(AimiTuningContext.HYPO_GUARD, effective)
    }

    @Test
    fun `hyper stable blocked when hypos elevated`() {
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.HYPER_STABLE,
            baseMetrics(timeBelow70 = 0.05, timeAbove180 = 0.20),
            mockPreferences(),
            t3cBrittleMode = false,
        )
        assertFalse(plan.isActionable)
        assertTrue(plan.blockedReason!!.contains("Hypo guard"))
    }

    @Test
    fun `hyper tier maps to moderate for 30 percent above 180`() {
        assertEquals(TuningStepTier.MODERATE, TuningContextEngine.hyperTier(0.30))
    }

    @Test
    fun `apply logs each preference change`() {
        val prefs = mockPreferences()
        every { prefs.put(any<DoubleKey>(), any()) } returns Unit
        every { prefs.put(any<BooleanKey>(), any()) } returns Unit
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.MEAL_RISE,
            baseMetrics(timeAbove180 = 0.35),
            prefs,
            t3cBrittleMode = false,
        )
        val history = mockk<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository>(relaxed = true)
        val result = TuningContextApplySupport.applyTuningPlan(plan, prefs, history)
        assertTrue(result.appliedCount > 0)
        verify(atLeast = 1) {
            history.logAction(
                app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE,
                any(),
                any(),
                any(),
                any(),
            )
        }
    }
}
