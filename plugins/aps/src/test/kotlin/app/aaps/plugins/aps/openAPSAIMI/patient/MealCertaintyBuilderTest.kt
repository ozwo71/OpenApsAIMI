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
}
