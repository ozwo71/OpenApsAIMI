package app.aaps.plugins.aps.openAPSAIMI.autodrive.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MpcControllerCandidateGridTest {

    @Test
    fun `candidate grid is capped`() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = 50.0, searchStep = 0.005)
        assertTrue(candidates.size <= MpcController.MAX_DOSE_CANDIDATES)
        assertEquals(0.0, candidates.first(), 0.0)
    }

    @Test
    fun `non finite max dose yields zero only`() {
        val candidates = MpcController.buildDoseCandidates(maxSafeDoseU = Double.NaN, searchStep = 0.005)
        assertEquals(listOf(0.0), candidates)
    }
}
