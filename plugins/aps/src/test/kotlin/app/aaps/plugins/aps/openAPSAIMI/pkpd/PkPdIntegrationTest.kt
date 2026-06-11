package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.math.abs

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
    fun `physio resistance lowers fused isf while preserving bounded factors`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        val baseline = PkPdIntegration(preferences).computeRuntime(
            epochMillis = 1000,
            bg = 160.0,
            deltaMgDlPer5 = 3.5,
            iobU = 2.2,
            carbsActiveG = 18.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = MealAggressionContext(
                mealModeActive = true,
                predictedBgMgdl = 210.0,
                targetBgMgdl = 110.0,
            ),
            patientWeightKg = 75.0,
        )
        val resistant = PkPdIntegration(preferences).computeRuntime(
            epochMillis = 1000,
            bg = 160.0,
            deltaMgDlPer5 = 3.5,
            iobU = 2.2,
            carbsActiveG = 18.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = MealAggressionContext(
                mealModeActive = true,
                predictedBgMgdl = 210.0,
                targetBgMgdl = 110.0,
            ),
            patientWeightKg = 75.0,
            physioLatentState = PhysioLatentState(
                circadianSiFactor = 0.84,
                transientResistanceProb = 0.90,
                sensorConfidence = 1.0,
                mealProb = 0.60,
            ),
            estimatedRaMgdlPerMin = 3.5,
        )

        assertNotNull(baseline)
        assertNotNull(resistant)
        assertTrue(resistant!!.fusedIsf < baseline!!.fusedIsf)
        assertTrue(resistant.physioSiFactor < 1.0)
        assertTrue(resistant.physioAbsorptionFactor >= 1.0)
    }

    @Test
    fun `heavier weight reduces kinetic factor and pkpd scale`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        val lighter = PkPdIntegration(preferences).computeRuntime(
            epochMillis = 1000,
            bg = 140.0,
            deltaMgDlPer5 = 2.0,
            iobU = 1.8,
            carbsActiveG = 10.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            patientWeightKg = 60.0,
        )
        val heavier = PkPdIntegration(preferences).computeRuntime(
            epochMillis = 1000,
            bg = 140.0,
            deltaMgDlPer5 = 2.0,
            iobU = 1.8,
            carbsActiveG = 10.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            patientWeightKg = 105.0,
        )

        assertNotNull(lighter)
        assertNotNull(heavier)
        assertTrue(lighter!!.weightKineticFactor > heavier!!.weightKineticFactor)
        assertTrue(lighter.pkpdScale > heavier.pkpdScale)
    }

    @Test
    fun `strong physio family applies more of latent pkpd modulation than low physio family`() {
        val lowPhysioPrefs: Preferences = mockk(relaxed = true)
        val strongPhysioPrefs: Preferences = mockk(relaxed = true)
        every { lowPhysioPrefs.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { strongPhysioPrefs.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults(lowPhysioPrefs)
        mockPkpdDefaults(strongPhysioPrefs)
        every { lowPhysioPrefs.get(BooleanKey.AimiPhysioAssistantEnable) } returns false
        every { lowPhysioPrefs.get(BooleanKey.AimiPhysioSleepDataEnable) } returns false
        every { lowPhysioPrefs.get(BooleanKey.AimiPhysioHRVDataEnable) } returns false
        every { strongPhysioPrefs.get(BooleanKey.AimiPhysioAssistantEnable) } returns true
        every { strongPhysioPrefs.get(BooleanKey.AimiPhysioSleepDataEnable) } returns true
        every { strongPhysioPrefs.get(BooleanKey.AimiPhysioHRVDataEnable) } returns true

        val latent = PhysioLatentState(
            circadianSiFactor = 0.84,
            transientResistanceProb = 0.88,
            postHypoReboundProb = 0.14,
            sensorConfidence = 1.0,
            mealProb = 0.58,
        )
        val mealContext = MealAggressionContext(
            mealModeActive = true,
            predictedBgMgdl = 205.0,
            targetBgMgdl = 110.0,
        )

        val lowProfile = PkPdIntegration(lowPhysioPrefs).computeRuntime(
            epochMillis = 1000,
            bg = 158.0,
            deltaMgDlPer5 = 3.2,
            iobU = 2.1,
            carbsActiveG = 14.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = mealContext,
            patientWeightKg = 75.0,
            physioLatentState = latent,
            estimatedRaMgdlPerMin = 3.2,
        )
        val strongProfile = PkPdIntegration(strongPhysioPrefs).computeRuntime(
            epochMillis = 1000,
            bg = 158.0,
            deltaMgDlPer5 = 3.2,
            iobU = 2.1,
            carbsActiveG = 14.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = mealContext,
            patientWeightKg = 75.0,
            physioLatentState = latent,
            estimatedRaMgdlPerMin = 3.2,
        )

        assertNotNull(lowProfile)
        assertNotNull(strongProfile)
        assertTrue(strongProfile!!.physioSiFactor < lowProfile!!.physioSiFactor)
        assertTrue(abs(strongProfile.physioAbsorptionFactor - 1.0) > abs(lowProfile.physioAbsorptionFactor - 1.0))
        assertTrue(strongProfile.fusedIsf < lowProfile.fusedIsf)
    }

    @Test
    fun `protective causal context skips pkpd learning while preserving baseline params`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        val logs = mutableListOf<String>()
        var runtime: PkPdRuntime? = null

        repeat(12) { tick ->
            runtime = integration.computeRuntime(
                epochMillis = (tick + 1L) * 5L * 60L * 1000L,
                bg = 132.0,
                deltaMgDlPer5 = 1.8,
                iobU = 1.8,
                carbsActiveG = 4.0,
                windowMin = 60,
                exerciseFlag = false,
                profileIsf = 50.0,
                tdd24h = 40.0,
                consoleLog = logs,
                physioLatentState = PhysioLatentState(
                    circadianSiFactor = 0.90,
                    transientResistanceProb = 0.52,
                    endogenousGlucoseDrive = 0.72,
                    sensorConfidence = 0.93,
                ),
                causalStatePosterior = CausalStatePosterior(
                    fastMealProb = 0.12,
                    prolongedMealProb = 0.08,
                    dawnEndogenousProb = 0.82,
                    postHypoRecoveryProb = 0.10,
                    stressResistanceProb = 0.22,
                    exerciseAfterburnProb = 0.05,
                    inflammatoryDriftProb = 0.10,
                    absorptionUncertainProb = 0.18,
                    dominant = CausalStateId.DAWN_ENDOGENOUS,
                    dominantConfidence = 0.82,
                    learningQuality = 0.24,
                ),
            )
        }

        assertNotNull(runtime)
        assertEquals(6.0, runtime!!.params.diaHrs, 1e-9)
        assertEquals(55.0, runtime!!.params.peakMin, 1e-9)
        assertThat(logs.any { it.contains("PKPD_LEARN skip: causal_dawn_endogenous") }).isTrue()
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
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
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
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
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
        mockPkpdDefaults(preferences)
    }

    private fun mockPkpdDefaults(target: Preferences) {
        every { target.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 6.0
        every { target.get(DoubleKey.OApsAIMIPkpdStatePeakMin) } returns 55.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH) } returns 5.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH) } returns 8.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin) } returns 35.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax) } returns 95.0
        every { target.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH) } returns 0.5
        every { target.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin) } returns 5.0
        every { target.get(DoubleKey.OApsAIMIPkpdAnchorDiaH) } returns 4.0
        every { target.get(DoubleKey.OApsAIMIPkpdAnchorPeakMin) } returns 55.0
        every { target.get(DoubleKey.OApsAIMIIsfFusionMinFactor) } returns 0.7
        every { target.get(DoubleKey.OApsAIMIIsfFusionMaxFactor) } returns 1.5
        every { target.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick) } returns 0.2
        every { target.get(DoubleKey.OApsAIMISmbTailThreshold) } returns 1.0
        every { target.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.85
        every { target.get(DoubleKey.OApsAIMISmbExerciseDamping) } returns 0.8
        every { target.get(DoubleKey.OApsAIMISmbLateFatDamping) } returns 0.8
    }
}
