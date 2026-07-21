package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class PkpdSoftFloorPathMinTest {

    @Test
    fun fromCurves_reportsApplied_whenEndoLiftedInsulin() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(180.0, 41.0, 55.0),
            cob = listOf(180.0, 50.0, 60.0),
            uam = listOf(180.0, 55.0, 65.0),
            zt = listOf(180.0, 41.0, 55.0),
            hybrid = listOf(180.0, 70.0, 80.0),
            insulinPathMinRawMgdl = 39.0,
            insulinPathMinSoftMgdl = 41.0,
            endogenousReversionOnInsulinCurves = true,
        )
        val telemetry = PkpdSoftFloorPathMin.fromCurves(curves, endogenousReversionEnabled = true)
        assertThat(telemetry.applied).isTrue()
        assertThat(telemetry.rawPathMinMgdl).isEqualTo(39.0)
        assertThat(telemetry.softPathMinMgdl).isEqualTo(80.0)
        assertThat(telemetry.reason).isEqualTo("egp_applied_on_insulin_curves")
        assertThat(telemetry.toJsonObject().getBoolean("shadow_only")).isFalse()
        assertThat(telemetry.toJsonObject().getBoolean("applied")).isTrue()
    }

    @Test
    fun fromCurves_disabledEndo_notApplied() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(180.0, 39.0),
            cob = listOf(180.0, 39.0),
            uam = listOf(180.0, 39.0),
            zt = listOf(180.0, 39.0),
            hybrid = listOf(180.0, 80.0),
            insulinPathMinRawMgdl = 39.0,
            insulinPathMinSoftMgdl = 39.0,
            endogenousReversionOnInsulinCurves = false,
        )
        val telemetry = PkpdSoftFloorPathMin.fromCurves(curves, endogenousReversionEnabled = false)
        assertThat(telemetry.applied).isFalse()
        assertThat(telemetry.reason).isEqualTo("endo_reversion_disabled")
    }

    @Test
    fun liftFloorBandPoints_raisesOnlyFloorBand() {
        val lifted = PkpdSoftFloorPathMin.liftFloorBandPoints(listOf(180, 39, 100, 42), 80.0)
        assertThat(lifted).containsExactly(180, 80, 100, 80).inOrder()
    }

    @Test
    fun predictCurves_endoRaisesIobWhenInsulinExhausted() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0
        val emptyIob = emptyArray<IobTotal>()
        val withEndo = AdvancedPredictionEngine.predictCurves(
            currentBG = 50.0,
            iobArray = emptyIob,
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 120,
            endogenousReversionEnabled = true,
        )
        val withoutEndo = AdvancedPredictionEngine.predictCurves(
            currentBG = 50.0,
            iobArray = emptyIob,
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 120,
            endogenousReversionEnabled = false,
        )
        assertThat(withEndo.endogenousReversionOnInsulinCurves).isTrue()
        assertThat(withEndo.iob.last()).isGreaterThan(withoutEndo.iob.last())
        assertThat(withEndo.iob.last()).isAtMost(80.0)
        assertThat(withEndo.zt.last()).isEqualTo(withEndo.iob.last())
    }
}
