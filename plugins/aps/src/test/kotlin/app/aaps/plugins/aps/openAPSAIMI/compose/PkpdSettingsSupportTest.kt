package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import org.junit.Assert.assertEquals
import org.junit.Test

class PkpdSettingsSupportTest {

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
}
