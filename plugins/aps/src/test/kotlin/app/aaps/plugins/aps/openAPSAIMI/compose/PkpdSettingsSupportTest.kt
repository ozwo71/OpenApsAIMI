package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PkpdSettingsSupportTest {

    /**
     * Guards the label ↔ mapping alignment for the "Late insulin action (SMB)" tail slider.
     *
     * The slider ends carry static labels rendered verbatim by `PkpdLabeledSlider` (no inversion):
     * left = `aimi_pkpd_tail_left`, right = `aimi_pkpd_tail_right`. The mapping runs
     * left (level 0) → mildest damping (allows MORE SMB) and right (level 1) → strongest damping
     * (MOST cautious). Correct labels are therefore left = "Allow more", right = "More cautious".
     * If the labels are ever swapped back to the corrections-slider convention ("More cautious" on
     * the left), they would silently re-invert a hypo-safety guard — this test pins the direction.
     */
    @Test
    fun `tail slider left end allows more, right end is most cautious`() {
        // Left end = mildest damping = highest floor = least SMB reduction → labelled "Allow more".
        assertEquals(PkpdSmbTailDamping.DAMPING_LIGHT, PkpdSmbTailDamping.dampingForSliderLevel(0.0), 0.001)
        // Right end = strongest damping = lowest floor = most SMB reduction → labelled "More cautious".
        assertEquals(PkpdSmbTailDamping.DAMPING_STRONG, PkpdSmbTailDamping.dampingForSliderLevel(1.0), 0.001)
        // Lower stored value = stronger guard, so the "cautious" (right) end must store LESS than the left end.
        assertTrue(
            PkpdSmbTailDamping.dampingForSliderLevel(1.0) < PkpdSmbTailDamping.dampingForSliderLevel(0.0),
            "Tail damping mapping inverted: right (cautious) end must store a lower floor than left (allow more) end"
        )
    }

    @Test
    fun `tail prudence round trip at center`() {
        val damping = PkpdTailPrudence.dampingForLevel(0.5)
        assertEquals(0.85, damping, 0.001)
        assertEquals(0.5, PkpdTailPrudence.readLevelFromDamping(damping), 0.001)
    }

    @Test
    fun `legacy tail damping 0_5 maps to center slider`() {
        assertEquals(0.5, PkpdTailPrudence.readLevelFromDamping(0.5), 0.001)
        assertEquals(0.85, PkpdSmbTailDamping.effectiveStoredValue(0.5), 0.001)
    }

    @Test
    fun `correction prudence round trip at center`() {
        val (min, max) = PkpdCorrectionPrudence.factorsForLevel(0.5)
        assertEquals(0.75, min, 0.001)
        assertEquals(1.25, max, 0.001)
        assertEquals(0.5, PkpdCorrectionPrudence.readLevelFromFactors(min, max), 0.001)
    }

    @Test
    fun `tail prudence does not map sub 0_72 to permissive max`() {
        assertEquals(0.5, PkpdTailPrudence.readLevelFromDamping(0.5), 0.001)
        assertEquals(1.0, PkpdTailPrudence.readLevelFromDamping(0.70), 0.02)
    }

    @Test
    fun `level 0_4 matches two steps left from center`() {
        val (min, max) = PkpdCorrectionPrudence.factorsForLevel(0.4)
        assertEquals(0.77, min, 0.01)
        assertEquals(1.22, max, 0.01)
    }

    @Test
    fun `slider endpoints map to expected preference values`() {
        assertEquals(0.85 to 1.10, PkpdCorrectionPrudence.factorsForLevel(0.0))
        assertEquals(0.65 to 1.40, PkpdCorrectionPrudence.factorsForLevel(1.0))
        assertEquals(0.92, PkpdTailPrudence.dampingForLevel(0.0), 0.001)
        assertEquals(0.70, PkpdTailPrudence.dampingForLevel(1.0), 0.001)
    }
}
