package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
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
        coordinator = BasalMlTrainingCoordinator(storage, preferences, learner, log)
    }

    @Test
    fun `parser builds aligned basal and t3c targets`() {
        val dataset = BasalMlDatasetParser.parse(csvFile)
        assertThat(dataset).isNotNull()
        assertThat(dataset!!.rowCount).isEqualTo(120)
        assertThat(dataset.basalTargets).hasSize(120)
        assertThat(dataset.t3cTargets).hasSize(120)
        val basalTarget = dataset.basalTargets.first()[0]
        val t3cTarget = dataset.t3cTargets.first()[0]
        assertThat(basalTarget).isAtLeast(0.7)
        assertThat(basalTarget).isAtMost(1.5)
        assertThat(t3cTarget).isAtLeast(0.5)
        assertThat(t3cTarget).isAtMost(2.0)
    }

    @Test
    fun `skips when both ML feature prefs are off`() = runBlocking {
        every { preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns false
        every { preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false

        val outcome = coordinator.runScheduledTraining()
        assertThat(outcome).isEqualTo(BasalMlTrainingCoordinator.TrainingOutcome.SKIPPED)
    }

    @Test
    fun `skips when fewer than min new rows since last train`() = runBlocking {
        val stateFile = File(tempDir, "basal_ml_training_state.json")
        stateFile.writeText("""{"lastTrainMs":0,"rowsAtLastTrain":110}""")

        val freshCoordinator = BasalMlTrainingCoordinator(storage, preferences, learner, mockk(relaxed = true))
        val outcome = freshCoordinator.runScheduledTraining()
        assertThat(outcome).isEqualTo(BasalMlTrainingCoordinator.TrainingOutcome.SKIPPED)
    }

    private fun writeSyntheticCsv(file: File, rowCount: Int) {
        val header = "bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,basalScale,t3cAgg"
        val lines = buildList {
            add(header)
            repeat(rowCount) { i ->
                val bg = 140.0 + (i % 10)
                add("$bg,${bg - 5},1.0,100,0.1,30,45,0.5,1.0,1.0")
            }
        }
        file.writeText(lines.joinToString("\n"))
    }
}
