package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalHypothesis
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhysiologicalTreeBuilderTest {

    @Test
    fun build_returnsNullWhenDisabled() {
        val state = stableState()
        val tree = PhysiologicalTreeBuilder.build(
            enabled = false,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
        )

        assertThat(tree).isNull()
    }

    @Test
    fun build_createsStableTreeWithMinimalCoherentData() {
        val state = stableState()
        val tree = buildTree(state)

        assertThat(tree).isNotNull()
        assertThat(tree?.trunk?.globalState).isEqualTo(GlobalPhysiologicalState.STABLE)
        assertThat(tree?.trunk?.dataCoherence).isEqualTo(DataCoherenceLevel.GOOD)
        assertThat(tree?.compactSummary).contains("Tree:")
        assertThat(tree?.compactSummary).contains("sensor ok")
    }

    @Test
    fun build_degradesWhenSensorConfidenceIsLow() {
        val state = stableState().copy(sensorConfidence = 0.22)
        val tree = buildTree(state)

        assertThat(tree?.trunk?.globalState).isEqualTo(GlobalPhysiologicalState.SENSOR_UNCERTAIN)
        assertThat(tree?.trunk?.dataCoherence).isEqualTo(DataCoherenceLevel.LOW)
        assertThat(tree?.branches?.sensorTrust?.detected).isFalse()
        assertThat(tree?.leaves?.safetyNotes?.joinToString()).contains("Sensor uncertainty")
    }

    @Test
    fun build_marksMealAndDigestionBranchesForFirstWave() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.MEAL_UNDECLARED,
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            mealAbsorptionBelief = 0.78,
            mealProb = 0.82,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.84,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        )
        val tree = buildTree(state)

        assertThat(tree?.branches?.digestion?.detected).isTrue()
        assertThat(tree?.branches?.meal?.detected).isTrue()
        assertThat(tree?.trunk?.globalState).isEqualTo(GlobalPhysiologicalState.DIGESTION_ACTIVE)
        assertThat(tree?.leaves?.mealAdvisorHints?.joinToString()).contains("Meal evidence")
    }

    @Test
    fun build_marksResistanceWhenEndogenousAndStressSignalsDominate() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            transientResistanceProb = 0.62,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.86,
                stressResistanceProb = 0.48,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.86,
                learningQuality = 0.70,
            ),
            falseMealSuppression = true,
        )
        val tree = buildTree(state)

        assertThat(tree?.branches?.hormonalResistance?.detected).isTrue()
        assertThat(tree?.trunk?.globalState).isEqualTo(GlobalPhysiologicalState.RESISTANCE_PROBABLE)
        assertThat(tree?.compactSummary).contains("resistance probable")
    }

    @Test
    fun build_keepsHypoRecoveryAsHighPriorityContext() {
        val state = stableState().copy(
            postHypoReboundProb = 0.72,
            causalPosterior = CausalStatePosterior(
                postHypoRecoveryProb = 0.78,
                dominant = CausalStateId.POST_HYPO_RECOVERY,
                dominantConfidence = 0.78,
                learningQuality = 0.40,
            ),
            eventMemory = PatientEventMemory(
                recentHypoLoad = 0.70,
                correctionFragilityScore = 0.66,
            ),
        )
        val tree = buildTree(state)

        assertThat(tree?.branches?.hypoRisk?.detected).isTrue()
        assertThat(tree?.trunk?.globalState).isEqualTo(GlobalPhysiologicalState.HYPO_RISK)
        assertThat(tree?.trunk?.riskLevel).isAtLeast(PhysiologicalRiskLevel.HIGH)
        assertThat(tree?.leaves?.noActionReasons).contains("hypo_or_recovery_risk")
    }

    @Test
    fun build_prefersHyperTrunkWhenStaleMemoryHypoDuringActiveHyperCorrection() {
        val state = stableState().copy(
            postHypoReboundProb = 0.0,
            mealProb = 0.90,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.90,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.90,
                learningQuality = 0.70,
            ),
            eventMemory = PatientEventMemory(
                recentHypoLoad = 1.0,
                recentHyperLoad = 0.70,
                correctionFragilityScore = 0.60,
                postHyperExhaustionScore = 0.80,
            ),
        )
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
            currentBgMgdl = 294.0,
            deltaMgdl5m = 8.0,
        )

        assertThat(tree?.trunk?.globalState).isNotEqualTo(GlobalPhysiologicalState.HYPO_RISK)
        assertThat(tree?.trunk?.riskLevel).isNotEqualTo(PhysiologicalRiskLevel.CRITICAL)
    }

    @Test
    fun build_handlesMissingWearableAndMlWithoutCrash() {
        val state = stableState().copy(
            sleepDebtScore = 0.40,
            eventMemory = PatientEventMemory(postHyperExhaustionScore = 0.45),
        )
        val tree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
            physioLive = PhysioLiveDigest(),
            thermalBelief = ThermalBeliefDigest(
                hypothesis = ThermalHypothesis.DATA_PENDING,
                confidence = 0.0,
            ),
        )

        assertThat(tree).isNotNull()
        assertThat(tree?.roots?.mlAsyncState?.safetyImpact).contains("no direct dosing")
        assertThat(tree?.branches?.sleepRecovery?.detected).isTrue()
    }

    @Test
    fun toJsonObject_exportsContextOnlyWithoutInsulinCommandFields() {
        val json = buildTree(stableState())!!.toJsonObject()
        val serialized = json.toString()

        assertThat(json.getString("insulin_authority")).isEqualTo("none_lot1_context_only")
        assertThat(serialized).doesNotContain("smb_u")
        assertThat(serialized).doesNotContain("tbr_uph")
        assertThat(serialized).doesNotContain("bolus_u")
    }

    private fun buildTree(state: PatientStateSnapshot): PhysiologicalTreeSnapshot? =
        PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
        )

    private fun stableState(): PatientStateSnapshot =
        PatientStateSnapshot(
            timestampMs = 1_718_000_000_000L,
            phase = PhysiologicalPhase.OFF,
            phaseConfidence = 0.50,
            mealAbsorptionPhase = MealAbsorptionPhase.NONE,
            mealAbsorptionBelief = 0.0,
            mealProb = 0.08,
            endogenousGlucoseDrive = 0.10,
            transientResistanceProb = 0.08,
            sleepDebtScore = 0.0,
            postHypoReboundProb = 0.0,
            sensorConfidence = 0.88,
            causalPosterior = CausalStatePosterior(
                dominant = CausalStateId.UNKNOWN,
                dominantConfidence = 0.0,
                learningQuality = 0.86,
            ),
        )
}
