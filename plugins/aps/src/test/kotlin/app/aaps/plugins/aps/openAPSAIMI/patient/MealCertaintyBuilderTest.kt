package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealCertaintyBuilderTest {

    @Test
    fun digestionRisingAboveBand_isHigh() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
            ),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.supportsMealOverProtective).isTrue()
        assertThat(mc.riseGeometry).isEqualTo(MealRiseGeometry.OK)
    }

    @Test
    fun fallingDelta_isNotHigh() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 280.0,
                deltaMgdl5m = -6.0,
                targetBgMgdl = 100.0,
            ),
        )
        assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.riseGeometry).isEqualTo(MealRiseGeometry.FALLING)
    }

    @Test
    fun effortVeto_blocksHighAndMedWhenUndeclared() {
        // Mild hyper + modest rise: effort veto must still pin LOW (real activity risk).
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
                cobG = 0.0,
                effortVeto = true,
            ),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.LOW)
        assertThat(mc.supportsMealSupport).isFalse()
    }

    @Test
    fun effortVeto_overriddenByStrongUndeclaredHyperRise() {
        // 25/07 14:42 style: BG≈248 Δ≈23 FIRST_WAVE + effort_veto from postprandial HR noise.
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 1.0,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 248.0,
                deltaMgdl5m = 22.7,
                targetBgMgdl = 100.0,
                cobG = 0.0,
                effortVeto = true,
            ),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.supportsMealOverProtective).isTrue()
        assertThat(mc.supportsMealSupport).isTrue()
        assertThat(mc.reasons).contains("level_high_digestion_overrides_effort_veto")
    }

    @Test
    fun hypoTerminalConflict_forcesNone() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
                scenarioPathMinMgdl = 60.0,
            ),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.NONE)
        assertThat(mc.terminalsAgree).isEqualTo(MealTerminalsAgree.HYPO_CONFLICT)
    }

    @Test
    fun mealProbableWithWeakRise_isMed() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.MEAL_PROBABLE,
                mealBranchConfidence = 0.6,
                bgMgdl = 150.0,
                deltaMgdl5m = 0.5,
                targetBgMgdl = 100.0,
            ),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.MED)
        assertThat(mc.supportsMealSupport).isTrue()
        assertThat(mc.supportsMealOverProtective).isFalse()
    }

    @Test
    fun pkpdFloorConflict_stillAllowsHighWhenPathSafe() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
                pkpdEventualMgdl = 39.0,
                scenarioTerminalMgdl = 180.0,
                scenarioPathMinMgdl = 95.0,
            ),
        )
        assertThat(mc.terminalsAgree).isEqualTo(MealTerminalsAgree.PKPD_FLOOR_CONFLICT)
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
    }

    @Test
    fun softCorroborationFromPhysio_requiresIdleElevatedHr() {
        assertThat(
            MealCertaintyBuilder.softCorroborationFromPhysio(
                PhysioLiveDigest(hrNowBpm = 80, rhrRestingBpm = 55, activityState = "IDLE", stepsLast15m = 10),
            ),
        ).isTrue()
        assertThat(
            MealCertaintyBuilder.softCorroborationFromPhysio(
                PhysioLiveDigest(hrNowBpm = 100, rhrRestingBpm = 55, activityState = "WALKING", stepsLast15m = 400),
            ),
        ).isFalse()
    }

    // --- effort SMB floor on a certain meal (2026-08-10 lunch) ---

    private fun certaintyAt(level: MealCertaintyLevel): MealCertainty =
        MealCertainty.NONE.copy(level = level)

    @Test
    fun effortSmbFactorFor_floorsTheReductionOnlyWhenTheMealIsCertain() {
        // The 12:36 tick of 2026-08-10: effort asked for 0.56 at BG 237 with the meal certain.
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.56))
            .isWithin(1e-9).of(0.75)
        // The dinner of 2026-08-10 never left LOW — it must not move.
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.LOW), 0.56))
            .isWithin(1e-9).of(0.56)
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.MED), 0.56))
            .isWithin(1e-9).of(0.56)
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(null, 0.56)).isWithin(1e-9).of(0.56)
    }

    @Test
    fun effortSmbFactorFor_neverRaisesAboveWhatEffortAsked_whenItAskedForMore() {
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.90))
            .isWithin(1e-9).of(0.90)
    }

    @Test
    fun effortSmbFactorFor_isAlwaysAReduction() {
        for (level in MealCertaintyLevel.entries) {
            assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(level), 1.0)).isAtMost(1.0)
            assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(level), 0.0)).isAtMost(1.0)
        }
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 1.4))
            .isWithin(1e-9).of(1.0)
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), Double.NaN))
            .isWithin(1e-9).of(1.0)
    }

    @Test
    fun effortSmbFactorFor_aTotalEffortStopStillKeepsAQuarterOnACertainMeal() {
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.0))
            .isWithin(1e-9).of(0.75)
    }
}
