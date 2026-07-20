package app.aaps.plugins.aps.openAPSAIMI.wcycle

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class EndocrineAmplitudeGovernorTest {

    @Test
    fun from_disabledInfo_returnsDisabledBelief() {
        val prefs = mockPrefs()
        val belief = EndocrineAmplitudeGovernor.from(info = null, prefs = prefs)
        assertThat(belief.enabled).isFalse()
        assertThat(belief.applicationMode).isEqualTo(EndocrineApplicationMode.DISABLED)
        assertThat(belief.effectiveBasalAmp).isEqualTo(1.0)
    }

    @Test
    fun from_lutealWithHypoLoad_dampensEffectiveBasalTowardUnity() {
        val prefs = mockPrefs()
        val info = lutealInfo(basal = 1.25, smb = 1.12, applied = true)
        val undamped = EndocrineAmplitudeGovernor.from(
            info = info,
            prefs = prefs,
            hypoLoad = 0.0,
            hypoGuardActive = false,
            hourOfDay = 12,
        )
        val dampened = EndocrineAmplitudeGovernor.from(
            info = info,
            prefs = prefs,
            hypoLoad = 0.80,
            hypoGuardActive = true,
            hourOfDay = 12,
        )
        assertThat(undamped.effectiveBasalAmp).isGreaterThan(1.15)
        assertThat(dampened.effectiveBasalAmp).isLessThan(undamped.effectiveBasalAmp)
        assertThat(dampened.hypoLoadDampen).isLessThan(1.0)
        assertThat(dampened.legacyDoseBasalAmp).isEqualTo(1.25)
        assertThat(dampened.dosePathOwner).isEqualTo(EndocrineDosePathOwner.LEGACY_DIRECT_SCALE)
    }

    @Test
    fun from_lutealDawnHour_setsDawnBias() {
        val prefs = mockPrefs()
        val info = lutealInfo(basal = 1.25, smb = 1.12, applied = true)
        val belief = EndocrineAmplitudeGovernor.from(
            info = info,
            prefs = prefs,
            hypoLoad = 0.0,
            hourOfDay = 5,
        )
        assertThat(belief.dawnBias).isEqualTo(1.10)
        assertThat(belief.reasons.joinToString()).contains("dawn_bias")
    }

    private fun mockPrefs(): WCyclePreferences {
        val prefs = mockk<WCyclePreferences>()
        every { prefs.trackingMode() } returns CycleTrackingMode.CALENDAR_VARIABLE
        every { prefs.contraceptive() } returns ContraceptiveType.COPPER_IUD
        every { prefs.thyroid() } returns ThyroidStatus.EUTHYROID
        every { prefs.verneuil() } returns VerneuilStatus.ACTIVE
        every { prefs.shadow() } returns false
        every { prefs.requireConfirm() } returns false
        every { prefs.clampMin() } returns 0.7
        every { prefs.clampMax() } returns 1.5
        return prefs
    }

    private fun lutealInfo(basal: Double, smb: Double, applied: Boolean): WCycleInfo =
        WCycleInfo(
            enabled = true,
            dayInCycle = 21,
            phase = CyclePhase.LUTEAL,
            baseBasalMultiplier = basal,
            baseSmbMultiplier = smb,
            learnedBasalMultiplier = 1.0,
            learnedSmbMultiplier = 1.0,
            basalMultiplier = if (applied) basal else 1.0,
            smbMultiplier = if (applied) smb else 1.0,
            icMultiplier = if (applied) 1.15 else 1.0,
            applied = applied,
            reason = "test",
        )
}
