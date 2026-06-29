package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmoniaSimulationEngineTest {

    @Test
    fun evaluate_returnsBasalFirstForResistanceWithoutPumpAuthority() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            transientResistanceProb = 0.70,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                stressResistanceProb = 0.62,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        )
        val decision = HarmoniaSimulationEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment(),
        )

        assertThat(decision?.eligible).isTrue()
        assertThat(decision?.action).isEqualTo(HarmoniaSimulationAction.BASAL_FIRST)
        assertThat(decision?.simulatedSmbU).isEqualTo(0.0)
        assertThat(decision?.toJsonObject()?.getBoolean("applies_to_pump")).isFalse()
        assertThat(decision?.compactSummary).contains("Harmonia sim:")
    }

    @Test
    fun evaluate_blocksWhenHypoRecoveryOrLowBgIsPresent() {
        val state = stableState().copy(
            postHypoReboundProb = 0.72,
            causalPosterior = CausalStatePosterior(
                postHypoRecoveryProb = 0.80,
                dominant = CausalStateId.POST_HYPO_RECOVERY,
                dominantConfidence = 0.80,
            ),
        )
        val decision = HarmoniaSimulationEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(currentBgMgdl = 76.0, deltaMgdl5m = -1.2),
        )

        assertThat(decision?.eligible).isFalse()
        assertThat(decision?.action).isEqualTo(HarmoniaSimulationAction.BLOCKED)
        assertThat(decision?.simulatedSmbU).isEqualTo(0.0)
        assertThat(decision?.blockers).contains("hypo_or_recovery")
    }

    @Test
    fun evaluate_capsBasalAndSmbInsideVirtualPumpLimits() {
        val state = stableState().copy(
            phase = PhysiologicalPhase.MEAL_UNDECLARED,
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            mealAbsorptionBelief = 0.80,
            mealProb = 0.88,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.90,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.90,
                learningQuality = 0.85,
            ),
        )
        val decision = HarmoniaSimulationEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(
                deltaMgdl5m = 4.0,
                currentBasalUph = 4.9,
                maxBasalUph = 5.0,
                maxSmbU = 0.12,
                iobU = 4.50,
                maxIobU = 5.0,
            ),
        )

        assertThat(decision?.eligible).isTrue()
        assertThat(decision?.simulatedBasalUph).isAtMost(5.0)
        assertThat(decision?.simulatedSmbU).isAtMost(0.10)
        assertThat(decision?.capsApplied).contains("pump_basal_cap_or_step")
        assertThat(decision?.capsApplied).contains("pump_smb_or_iob_cap")
    }

    @Test
    fun randomizedEnvironment_isDeterministicForReplay() {
        val first = HarmoniaSimulationEngine.randomizedEnvironment(seed = 42L)
        val second = HarmoniaSimulationEngine.randomizedEnvironment(seed = 42L)

        assertThat(first).isEqualTo(second)
        assertThat(first.seed).isEqualTo(42L)
    }

    @Test
    fun productionDecision_exportsBasalFirstContractWithoutSmbAuthority() {
        val decision = HarmoniaProductionDecision(
            timestampMs = 1_718_000_000_000L,
            mode = HarmoniaProductionMode.APPLIED,
            selectedForProduction = true,
            requestedRateUph = 2.4,
            boundedRateUph = 1.3,
            appliedRateUph = 1.3,
            appliedDurationMin = 30,
            runtimeBlocker = null,
            safetyBlockers = emptyList(),
            sourceAction = HarmoniaSimulationAction.BASAL_FIRST,
            branch = "RESISTANT",
            reason = "harmonia_basal_first_applied",
        ).toJsonObject()

        assertThat(decision.getBoolean("basal_first_only")).isTrue()
        assertThat(decision.getBoolean("adds_smb_authority")).isFalse()
        assertThat(decision.getBoolean("applies_to_pump")).isTrue()
        assertThat(decision.getString("mode")).isEqualTo("APPLIED")
        assertThat(decision.getDouble("bounded_rate_uph")).isEqualTo(1.3)
    }

    @Test
    fun evaluate_fragilityTriggersStabilize() {
        val state = stableState().copy(
            postHypoReboundProb = 0.35,
            causalPosterior = CausalStatePosterior(
                postHypoRecoveryProb = 0.20,
                dominant = CausalStateId.UNKNOWN,
                dominantConfidence = 0.0,
            ),
        )
        val decision = HarmoniaSimulationEngine.evaluate(
            tree = buildTree(state),
            environment = safeEnvironment().copy(
                correctionFragilityScore = 0.62,
                postHyperExhaustionScore = 0.30,
            ),
        )

        assertThat(decision?.action).isEqualTo(HarmoniaSimulationAction.STABILIZE)
        assertThat(decision?.simulatedBasalUph).isLessThan(safeEnvironment().currentBasalUph)
    }

    private fun buildTree(state: PatientStateSnapshot): PhysiologicalTreeSnapshot? =
        PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
        )

    private fun safeEnvironment(): HarmoniaSimulationEnvironment =
        HarmoniaSimulationEnvironment(
            currentBgMgdl = 190.0,
            deltaMgdl5m = 2.5,
            iobU = 1.0,
            cobG = 10.0,
            currentBasalUph = 1.0,
            maxBasalUph = 5.0,
            maxSmbU = 1.0,
            maxIobU = 5.0,
            sensorAgeMin = 2,
            sensorNoise = 0.1,
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
