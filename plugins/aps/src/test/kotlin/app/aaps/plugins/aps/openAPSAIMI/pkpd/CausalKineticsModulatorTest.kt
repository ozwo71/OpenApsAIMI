package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalKineticsModulatorTest {

    @Test
    fun postHypoExtendsTailAndBlocksLearnWhenGateFails() {
        val posterior = CausalStatePosterior(
            postHypoRecoveryProb = 0.9,
            dominant = CausalStateId.POST_HYPO_RECOVERY,
            dominantConfidence = 0.8,
            learningQuality = 0.3,
        )
        val mod = CausalKineticsModulator.modulate(posterior)
        assertEquals(CausalStateId.POST_HYPO_RECOVERY, mod.dominant)
        assertFalse(mod.learningAllowed)
        assertTrue(mod.tailScale > 1.0)
    }

    @Test
    fun neutralPosteriorAllowsLearning() {
        val mod = CausalKineticsModulator.modulate(null)
        assertEquals(CausalStateId.UNKNOWN, mod.dominant)
        assertTrue(mod.learningAllowed)
    }
}
