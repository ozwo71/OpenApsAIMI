package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaGovernorTest {

    @Test
    fun blendsLearnedDiaWithProfile() {
        val r = DiaGovernor.resolve(
            profileDiaHours = 6.0,
            contextualDiaShiftHours = 0.0,
            pkpdLearnedDiaHours = 4.1,
            pkpdEnabled = true,
            governorEnabled = true,
            diaMinBound = 4.0,
            diaMaxBound = 9.0,
            learnedBlendWeight = 0.45,
        )
        assertTrue(r.appliedGovernor)
        assertEquals(5.05, r.effectiveDiaHours, 0.05)
        assertEquals("LEARNED", r.dominantBranch)
    }

    @Test
    fun fallsBackToAnchorWhenDisabled() {
        val r = DiaGovernor.resolve(
            profileDiaHours = 6.0,
            contextualDiaShiftHours = 0.2,
            pkpdLearnedDiaHours = 4.0,
            pkpdEnabled = true,
            governorEnabled = false,
            diaMinBound = 4.0,
            diaMaxBound = 9.0,
            learnedBlendWeight = 0.5,
        )
        assertEquals(6.2, r.effectiveDiaHours, 0.01)
        assertEquals(false, r.appliedGovernor)
    }
}
