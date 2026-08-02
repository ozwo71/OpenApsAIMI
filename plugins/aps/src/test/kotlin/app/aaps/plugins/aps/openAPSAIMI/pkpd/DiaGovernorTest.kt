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
        // blended = anchor * (1 - w) + learned * w = 6.0 * 0.55 + 4.1 * 0.45 = 5.145
        // (l'attente historique de 5.05 correspondait à une moyenne 50/50, antérieure à la prise en
        // compte de learnedBlendWeight).
        assertEquals(5.145, r.effectiveDiaHours, 1e-6)
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
