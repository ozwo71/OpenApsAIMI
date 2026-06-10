package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IsfBlenderTest {

    @Test
    fun `test blend rate limiting`() {
        val blender = IsfBlender(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L

        // First call has no history: elapsed = 0 -> zero budget -> anchored to the slow fusedIsf.
        // (The old expectation of an unconstrained 75.0 first blend predates this conservative anchor.)
        val result1 = blender.blend(50.0, 100.0, 0.5, now)
        assertEquals(50.0, result1, 0.01)

        // Immediate next call with same timestamp: zero elapsed budget, clamped to previous.
        val result2 = blender.blend(100.0, 200.0, 0.5, now)
        assertEquals(50.0, result2, 0.01)

        // 1 hour later: budget = min(5%/loop, 20%/h * 1h) = 5% per call.
        // Target 150 (blend of 100, 200) clamped to 50 * 1.05 = 52.5.
        val later = now + 3600000L
        val result3 = blender.blend(100.0, 200.0, 0.5, later)
        assertEquals(52.5, result3, 0.01)
    }
}
