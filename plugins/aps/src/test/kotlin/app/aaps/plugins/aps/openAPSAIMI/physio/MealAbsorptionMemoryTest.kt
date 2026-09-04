package app.aaps.plugins.aps.openAPSAIMI.physio

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Checks the episode onset stamp of [MealAbsorptionMemory]: it must mark the start of the episode,
 * not the last time the episode was seen.
 */
class MealAbsorptionMemoryTest {

    private val t0 = 1_700_000_000_000L

    @BeforeEach
    fun setUp() = MealAbsorptionMemory.reset()

    @AfterEach
    fun tearDown() = MealAbsorptionMemory.reset()

    private fun output(phase: MealAbsorptionPhase) = MealAbsorptionPhaseEngine.Output(
        phase = phase,
        belief = 0.5,
        reason = "test",
        deltaMgdlPer5 = 1.0,
        gapMgdl = 10.0,
        bestTerminalMgdl = 150.0,
        memoryActive = phase.isActive,
        waveCount = 1,
        mealDeliveryPriority = false,
        chronoPrior = 0.0,
        kineticScore = 0.0,
        trajectoryScore = 0.0,
        physioScore = 0.0
    )

    @Test
    fun `onset is set once when the episode starts and does not move on later active ticks`() {
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0)
        assertEquals(t0, MealAbsorptionMemory.onsetAtMs)

        val later = t0 + 30 * 60_000L
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.SECOND_WAVE), later)
        assertEquals(t0, MealAbsorptionMemory.onsetAtMs)
        // Last activity does move, the onset does not. That is the whole point of the new field.
        assertEquals(later, MealAbsorptionMemory.lastActiveAtMs)
        assertEquals(30.0, MealAbsorptionMemory.onsetAgeMin(later)!!, 1e-9)
    }

    @Test
    fun `reset clears the onset`() {
        MealAbsorptionMemory.update(output(MealAbsorptionPhase.FIRST_WAVE), t0)
        assertNotNull(MealAbsorptionMemory.onsetAgeMin(t0))

        MealAbsorptionMemory.reset()
        assertEquals(0L, MealAbsorptionMemory.onsetAtMs)
        assertNull(MealAbsorptionMemory.onsetAgeMin(t0))
    }

    @Test
    fun `onsetAgeMin is null when there is no episode`() {
        assertNull(MealAbsorptionMemory.onsetAgeMin(t0))
    }
}
