package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BasalMlTrainingCoordinatorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var csvFile: File
    private lateinit var storage: AimiStorageHelper
    private lateinit var preferences: Preferences
    private lateinit var learner: BasalNeuralLearner
    private lateinit var coordinator: BasalMlTrainingCoordinator

    @BeforeEach
    fun setup() {
        csvFile = File(tempDir, "basal_adaptive_records.csv")
        writeSyntheticCsv(csvFile, rowCount = 120)

        val context = mockk<Context>(relaxed = true)
        preferences = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        val log = mockk<AAPSLogger>(relaxed = true)

        every { storage.getAimiFile("basal_adaptive_records.csv") } returns csvFile
        every { storage.getAimiFile("basal_adaptive_weights.json") } returns File(tempDir, "basal_adaptive_weights.json")
        every { storage.getAimiFile("t3c_brain_weights.json") } returns File(tempDir, "t3c_brain_weights.json")
        every { storage.getAimiFile("basal_ml_training_state.json") } returns File(tempDir, "basal_ml_training_state.json")

        every { preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns true
        every { preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false

        learner = BasalNeuralLearner(context, preferences, storage, log)
        coordinator = BasalMlTrainingCoordinator(storage, learner, log)
    }

    @Test
    fun `parser aligns labels on realized future bg not floored eventualBg`() {
        val dataset = BasalMlDatasetParser.parse(csvFile)
        assertThat(dataset).isNotNull()
        // Tail rows without an observable +30min future are dropped (no fabricated label).
        assertThat(dataset!!.rowCount).isGreaterThan(100)
        assertThat(dataset.rowCount).isLessThan(120)
        assertThat(dataset.basalTargets).hasSize(dataset.rowCount)
        assertThat(dataset.t3cTargets).hasSize(dataset.rowCount)
        val basalTarget = dataset.basalTargets.first()[0]
        val t3cTarget = dataset.t3cTargets.first()[0]
        assertThat(basalTarget).isAtLeast(0.7)
        assertThat(basalTarget).isAtMost(1.5)
        assertThat(t3cTarget).isAtLeast(0.5)
        assertThat(t3cTarget).isAtMost(2.0)
        // Regression guard: BG stayed high (145 > target) so the label must push basal UP. If the parser still
        // used the floored eventualBg=39 as the outcome, actualDelta would be huge and it would learn to CUT
        // (≈0.71) instead — the exact contamination this fix removes.
        assertThat(basalTarget).isAtLeast(1.0)
    }

    @Test
    fun `training is reached even when both feature prefs are off (decoupled from usage)`() = runBlocking {
        // Old behavior returned SKIPPED before any training when the feature prefs were off. New contract: training
        // depends only on data availability; the prefs gate only runtime usage (BasalNeuralLearner). We assert the
        // training path is REACHED — deterministically, via the model-store read that trainAndMaybePublish performs
        // before training — rather than the stochastic publish result (the net is unseeded).
        mockkObject(BasalMlModelStore)
        try {
            every { BasalMlModelStore.loadValid(any(), any()) } returns null
            every { preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns false
            every { preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false

            coordinator.runScheduledTraining()

            // Reached only if the pref-gate is gone (both heads read their incumbent before training).
            verify(atLeast = 1) { BasalMlModelStore.loadValid(any(), any()) }
        } finally {
            unmockkObject(BasalMlModelStore)
        }
    }

    @Test
    fun `skips when fewer than min new rows since last train`() = runBlocking {
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        stateFile.writeText("""{"lastTrainMs":0,"rowsAtLastTrain":110}""")

        val freshCoordinator = BasalMlTrainingCoordinator(storage, learner, mockk(relaxed = true))
        val outcome = freshCoordinator.runScheduledTraining()
        assertThat(outcome).isEqualTo(BasalMlTrainingCoordinator.TrainingOutcome.SKIPPED)
    }

    private fun writeSyntheticCsv(file: File, rowCount: Int) {
        // Real column order (parser matches by name). 5-min cadence for timestamp-based label alignment.
        // BG held high at 145 (> target 100); eventualBg deliberately floored to 39 to prove the label now
        // comes from the realized future bg, not this prediction column.
        val header = "timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale"
        val startTs = 1_700_000_000_000L
        val stepMs = 5L * 60_000
        val lines = buildList {
            add(header)
            repeat(rowCount) { i ->
                val ts = startTs + i * stepMs
                add("$ts,145.0,39.0,1.0,100,0.1,30,45,0.5,1.0,1.0")
            }
        }
        file.writeText(lines.joinToString("\n"))
    }
}
