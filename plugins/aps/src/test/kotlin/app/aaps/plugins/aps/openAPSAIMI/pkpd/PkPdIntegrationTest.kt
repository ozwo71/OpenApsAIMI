package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkPdIntegrationTest {

    private val preferences: Preferences = mockk(relaxed = true)
    private val integration = PkPdIntegration(preferences)

    @Test
    fun `test computeRuntime disabled`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns false
        
        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )
        assertNull(result)
    }

    @Test
    fun `test computeRuntime enabled`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        
        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )
        assertNotNull(result)
    }

    @Test
    fun `aggregated stage does not reset to pre-onset in smb chain`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        integration.setRecentBolusSamples(
            listOf(
                PkpdBolusSample(ageMin = 25.0, units = 1.0),
                PkpdBolusSample(ageMin = 20.0, units = 1.0),
                PkpdBolusSample(ageMin = 15.0, units = 1.0),
                PkpdBolusSample(ageMin = 10.0, units = 1.0),
                PkpdBolusSample(ageMin = 5.0, units = 1.0),
            )
        )

        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 180.0,
            deltaMgDlPer5 = 8.0,
            iobU = 5.0,
            carbsActiveG = 20.0,
            windowMin = 5,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )

        assertNotNull(result)
        assertNotEquals(InsulinActivityStage.PRE_ONSET, result?.activity?.stage)
    }

    @Test
    fun `single recent bolus keeps pre-onset stage`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        integration.setRecentBolusSamples(listOf(PkpdBolusSample(ageMin = 5.0, units = 1.0)))

        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 120.0,
            deltaMgDlPer5 = 1.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 5,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )

        assertNotNull(result)
        assertEquals(InsulinActivityStage.PRE_ONSET, result?.activity?.stage)
    }

    @Test
    fun `learned DIA persists after small in-memory drift`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        val diaSlot = slot<Double>()
        every { preferences.put(DoubleKey.OApsAIMIPkpdStateDiaH, capture(diaSlot)) } returns Unit
        every { preferences.put(DoubleKey.OApsAIMIPkpdStatePeakMin, any()) } returns Unit

        repeat(80) { tick ->
            integration.computeRuntime(
                epochMillis = (tick + 1L) * 5L * 60L * 1000L,
                bg = 110.0,
                deltaMgDlPer5 = -12.0,
                iobU = 2.5,
                carbsActiveG = 0.0,
                windowMin = 60,
                exerciseFlag = false,
                profileIsf = 50.0,
                tdd24h = 40.0,
            )
        }

        verify(atLeast = 1) { preferences.put(DoubleKey.OApsAIMIPkpdStateDiaH, any()) }
        assertTrue(diaSlot.isCaptured)
        assertTrue(diaSlot.captured < 6.0)
    }

    @Test
    fun `tail policy change does not wipe in-memory learned DIA`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        repeat(60) { tick ->
            integration.computeRuntime(
                epochMillis = (tick + 1L) * 5L * 60L * 1000L,
                bg = 110.0,
                deltaMgDlPer5 = -12.0,
                iobU = 2.5,
                carbsActiveG = 0.0,
                windowMin = 60,
                exerciseFlag = false,
                profileIsf = 50.0,
                tdd24h = 40.0,
            )
        }
        val learnedBeforeTailChange = integration.computeRuntime(
            epochMillis = 61L * 5L * 60L * 1000L,
            bg = 110.0,
            deltaMgDlPer5 = -12.0,
            iobU = 2.5,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
        )?.params?.diaHrs
        assertNotNull(learnedBeforeTailChange)
        assertTrue(learnedBeforeTailChange!! < 5.99)

        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.70

        val afterTailChange = integration.computeRuntime(
            epochMillis = 62L * 5L * 60L * 1000L,
            bg = 110.0,
            deltaMgDlPer5 = -12.0,
            iobU = 2.5,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
        )?.params?.diaHrs

        assertNotNull(afterTailChange)
        assertEquals(learnedBeforeTailChange, afterTailChange!!, 0.02)
    }

    private fun mockPkpdDefaults() {
        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 6.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin) } returns 55.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH) } returns 5.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH) } returns 8.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin) } returns 35.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax) } returns 95.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH) } returns 0.5
        every { preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin) } returns 5.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdAnchorDiaH) } returns 4.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdAnchorPeakMin) } returns 55.0
        every { preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor) } returns 0.7
        every { preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor) } returns 1.5
        every { preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick) } returns 0.2
        every { preferences.get(DoubleKey.OApsAIMISmbTailThreshold) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.85
        every { preferences.get(DoubleKey.OApsAIMISmbExerciseDamping) } returns 0.8
        every { preferences.get(DoubleKey.OApsAIMISmbLateFatDamping) } returns 0.8
    }
}
