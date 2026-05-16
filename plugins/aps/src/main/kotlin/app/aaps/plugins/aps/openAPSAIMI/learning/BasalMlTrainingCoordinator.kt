package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Coordinated basal / T3C neural training with SMB-style safety:
 * rate limit, min new rows, circuit breaker, validation gate, atomic weights.
 */
@Singleton
class BasalMlTrainingCoordinator @Inject constructor(
    private val storageHelper: AimiStorageHelper,
    private val preferences: Preferences,
    private val basalNeuralLearner: BasalNeuralLearner,
    private val log: AAPSLogger,
) {

    companion object {
        private const val TAG = "BasalMlTraining"

        const val INPUT_SIZE = 6
        private const val TRAIN_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val MIN_NEW_ROWS = 80L
        private const val BASAL_MIN_ROWS = 100
        private const val T3C_MIN_ROWS = 50
        private const val CB_MAX_FAILURES = 3
        private const val CB_COOLDOWN_MS = 6L * 60 * 60 * 1000
        private const val VAL_LOSS_TOLERANCE = 1.05
        private const val STATE_FILE = "basal_ml_training_state.json"
        private const val CSV_FILE = "basal_adaptive_records.csv"
        private const val BASAL_WEIGHTS = "basal_adaptive_weights.json"
        private const val T3C_WEIGHTS = "t3c_brain_weights.json"

        @Volatile
        var instance: BasalMlTrainingCoordinator? = null
            internal set
    }

    enum class TrainingOutcome {
        SUCCESS,
        SKIPPED,
        FAILED_RETRY,
    }

    private val trainMutex = Mutex()
    private val lastTrainMs = AtomicLong(0L)
    private val rowsAtLastTrain = AtomicLong(0L)
    private val cbFailures = AtomicInteger(0)
    private val cbCoolingUntilMs = AtomicLong(0L)

    init {
        instance = this
        loadPersistedState()
    }

    suspend fun runScheduledTraining(): TrainingOutcome = trainMutex.withLock {
        val now = System.currentTimeMillis()
        if (isCircuitOpen(now)) {
            log.debug(LTag.APS, "$TAG: circuit breaker open — skip")
            return TrainingOutcome.SKIPPED
        }
        if (now - lastTrainMs.get() < TRAIN_INTERVAL_MS) {
            log.debug(LTag.APS, "$TAG: rate limit — skip")
            return TrainingOutcome.SKIPPED
        }

        val csvFile = storageHelper.getAimiFile(CSV_FILE)
        if (!csvFile.exists()) {
            log.debug(LTag.APS, "$TAG: CSV missing — skip")
            return TrainingOutcome.SKIPPED
        }

        val parsed = BasalMlDatasetParser.parse(csvFile) ?: run {
            log.debug(LTag.APS, "$TAG: CSV parse failed — skip")
            return TrainingOutcome.SKIPPED
        }

        val totalRows = parsed.rowCount.toLong()
        val newRows = totalRows - rowsAtLastTrain.get()
        if (newRows < MIN_NEW_ROWS) {
            log.debug(LTag.APS, "$TAG: only $newRows new rows (need $MIN_NEW_ROWS) — skip")
            return TrainingOutcome.SKIPPED
        }

        val trainBasal = preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
        val trainT3c = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
        if (!trainBasal && !trainT3c) {
            log.debug(LTag.APS, "$TAG: basal/T3C ML prefs off — skip")
            return TrainingOutcome.SKIPPED
        }

        return try {
            var published = false
            var hardFailure = false

            if (trainBasal && parsed.rowCount >= BASAL_MIN_ROWS) {
                when (trainBasalHead(parsed)) {
                    HeadTrainResult.PUBLISHED -> published = true
                    HeadTrainResult.FAILED -> hardFailure = true
                    HeadTrainResult.SKIPPED -> Unit
                }
            }

            if (trainT3c && parsed.rowCount >= T3C_MIN_ROWS) {
                when (trainT3cHead(parsed)) {
                    HeadTrainResult.PUBLISHED -> published = true
                    HeadTrainResult.FAILED -> hardFailure = true
                    HeadTrainResult.SKIPPED -> Unit
                }
            }

            when {
                hardFailure -> {
                    recordFailure()
                    TrainingOutcome.FAILED_RETRY
                }
                published -> {
                    lastTrainMs.set(now)
                    rowsAtLastTrain.set(totalRows)
                    cbFailures.set(0)
                    persistState()
                    basalNeuralLearner.reloadModels()
                    log.info(LTag.APS, "$TAG: training complete — models reloaded ($totalRows CSV rows)")
                    TrainingOutcome.SUCCESS
                }
                else -> TrainingOutcome.SKIPPED
            }
        } catch (e: Exception) {
            recordFailure()
            log.error(LTag.APS, "$TAG: training exception", e)
            TrainingOutcome.FAILED_RETRY
        }
    }

    private enum class HeadTrainResult { PUBLISHED, SKIPPED, FAILED }

    private fun trainBasalHead(parsed: BasalMlDataset): HeadTrainResult {
        val split = parsed.split80_20()
        val weightsFile = storageHelper.getAimiFile(BASAL_WEIGHTS)
        val config = TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20)
        val published = trainAndMaybePublish(
            weightsFile = weightsFile,
            trainInputs = split.trainInputs,
            trainTargets = split.trainBasalTargets,
            valInputs = split.valInputs,
            valTargets = split.valBasalTargets,
            config = config,
            outputRange = 0.5..2.0,
        )
        return when {
            published -> HeadTrainResult.PUBLISHED
            else -> HeadTrainResult.SKIPPED
        }
    }

    private fun trainT3cHead(parsed: BasalMlDataset): HeadTrainResult {
        val split = parsed.split80_20()
        val weightsFile = storageHelper.getAimiFile(T3C_WEIGHTS)
        val config = TrainingConfig(learningRate = 0.001, epochs = 300, patience = 20)
        val published = trainAndMaybePublish(
            weightsFile = weightsFile,
            trainInputs = split.trainInputs,
            trainTargets = split.trainT3cTargets,
            valInputs = split.valInputs,
            valTargets = split.valT3cTargets,
            config = config,
            outputRange = 0.3..3.0,
        )
        return when {
            published -> HeadTrainResult.PUBLISHED
            else -> HeadTrainResult.SKIPPED
        }
    }

    private fun trainAndMaybePublish(
        weightsFile: File,
        trainInputs: List<FloatArray>,
        trainTargets: List<DoubleArray>,
        valInputs: List<FloatArray>,
        valTargets: List<DoubleArray>,
        config: TrainingConfig,
        outputRange: ClosedFloatingPointRange<Double>,
    ): Boolean {
        if (trainInputs.isEmpty() || valInputs.isEmpty()) return false

        val incumbent = BasalMlModelStore.loadValid(weightsFile, INPUT_SIZE)
        val incumbentLoss = incumbent?.validate(valInputs, valTargets) ?: Double.MAX_VALUE

        val candidate = AimiNeuralNetwork(
            inputSize = INPUT_SIZE,
            hiddenSize = 8,
            outputSize = 1,
            config = config,
        )
        candidate.trainWithValidation(trainInputs, trainTargets, valInputs, valTargets)

        val probeOut = candidate.predict(FloatArray(INPUT_SIZE) { 0f })
        if (!probeOut.all { it.isFinite() }) {
            log.warn(LTag.APS, "$TAG: candidate probe NaN/Inf — discard")
            return false
        }
        val probeVal = probeOut.first().toDouble()
        if (probeVal !in outputRange) {
            log.warn(LTag.APS, "$TAG: candidate probe $probeVal outside $outputRange — discard")
            return false
        }

        val candidateLoss = candidate.lastBestValidationLoss()
        if (!candidateLoss.isFinite()) return false

        val maxAllowed = incumbentLoss * VAL_LOSS_TOLERANCE + 1e-6
        if (incumbent != null && candidateLoss > maxAllowed) {
            log.info(
                LTag.APS,
                "$TAG: reject ${weightsFile.name} val=$candidateLoss > incumbent=$incumbentLoss (tol=$VAL_LOSS_TOLERANCE)",
            )
            return false
        }

        if (!BasalMlModelStore.saveAtomic(weightsFile, candidate)) {
            log.warn(LTag.APS, "$TAG: atomic save failed for ${weightsFile.name}")
            return false
        }
        log.info(LTag.APS, "$TAG: published ${weightsFile.name} (val loss=$candidateLoss)")
        return true
    }

    private fun isCircuitOpen(now: Long): Boolean =
        cbFailures.get() >= CB_MAX_FAILURES && now < cbCoolingUntilMs.get()

    private fun recordFailure() {
        val failures = cbFailures.incrementAndGet()
        if (failures >= CB_MAX_FAILURES) {
            cbCoolingUntilMs.set(System.currentTimeMillis() + CB_COOLDOWN_MS)
            log.warn(LTag.APS, "$TAG: circuit breaker OPEN for 6h after $failures failures")
        }
    }

    private fun loadPersistedState() {
        val stateFile = storageHelper.getAimiFile(STATE_FILE)
        if (!stateFile.exists()) return
        try {
            val json = JSONObject(stateFile.readText())
            lastTrainMs.set(json.optLong("lastTrainMs", 0L))
            rowsAtLastTrain.set(json.optLong("rowsAtLastTrain", 0L))
        } catch (e: Exception) {
            log.warn(LTag.APS, "$TAG: could not load training state", e)
        }
    }

    private fun persistState() {
        try {
            val stateFile = storageHelper.getAimiFile(STATE_FILE)
            stateFile.parentFile?.mkdirs()
            val json = JSONObject()
                .put("lastTrainMs", lastTrainMs.get())
                .put("rowsAtLastTrain", rowsAtLastTrain.get())
            stateFile.writeText(json.toString())
        } catch (e: Exception) {
            log.warn(LTag.APS, "$TAG: could not persist training state", e)
        }
    }
}

/** Parsed rows from [basal_adaptive_records.csv] with basal and T3C labels. */
internal data class BasalMlDataset(
    val inputs: List<FloatArray>,
    val basalTargets: List<DoubleArray>,
    val t3cTargets: List<DoubleArray>,
) {
    val rowCount: Int get() = inputs.size

    data class Split(
        val trainInputs: List<FloatArray>,
        val valInputs: List<FloatArray>,
        val trainBasalTargets: List<DoubleArray>,
        val valBasalTargets: List<DoubleArray>,
        val trainT3cTargets: List<DoubleArray>,
        val valT3cTargets: List<DoubleArray>,
    )

    fun split80_20(): Split {
        val splitIdx = (inputs.size * 0.8).toInt().coerceAtLeast(1).coerceAtMost(inputs.size - 1)
        return Split(
            trainInputs = inputs.subList(0, splitIdx),
            valInputs = inputs.subList(splitIdx, inputs.size),
            trainBasalTargets = basalTargets.subList(0, splitIdx),
            valBasalTargets = basalTargets.subList(splitIdx, basalTargets.size),
            trainT3cTargets = t3cTargets.subList(0, splitIdx),
            valT3cTargets = t3cTargets.subList(splitIdx, t3cTargets.size),
        )
    }
}

internal object BasalMlDatasetParser {

    fun parse(csvFile: File): BasalMlDataset? {
        val allLines = csvFile.readLines()
        if (allLines.size < 2) return null

        val header = allLines.first().split(",")
        val iBg = header.indexOf("bg")
        val iEventual = header.indexOf("eventualBg")
        val iBasal = header.indexOf("basal")
        val iTarget = header.indexOf("target")
        val iAccel = header.indexOf("accel")
        val iDuraMin = header.indexOf("duraMin")
        val iDuraAvg = header.indexOf("duraAvg")
        val iIob = header.indexOf("iob")
        val iBasalScale = header.indexOf("basalScale")
        val iT3cAgg = header.indexOf("t3cAgg")

        val required = listOf(iBg, iEventual, iBasal, iTarget, iAccel, iDuraMin, iDuraAvg, iIob, iBasalScale, iT3cAgg)
        if (required.any { it < 0 }) return null

        val inputs = mutableListOf<FloatArray>()
        val basalTargets = mutableListOf<DoubleArray>()
        val t3cTargets = mutableListOf<DoubleArray>()

        for (line in allLines.drop(1)) {
            val cols = line.split(",")
            if (cols.size < header.size) continue

            val inputFeatures = floatArrayOf(
                cols[iBg].toFloat(),
                cols[iBasal].toFloat(),
                cols[iAccel].toFloat(),
                cols[iDuraMin].toFloat(),
                cols[iDuraAvg].toFloat(),
                cols[iIob].toFloat(),
            )

            val bgBefore = cols[iBg].toDouble()
            val bgAfter = cols[iEventual].toDouble()
            val targetBg = cols[iTarget].toDouble()
            val currentScale = cols[iBasalScale].toDouble()
            val currentAgg = cols[iT3cAgg].toDouble()

            val actualDelta = bgBefore - bgAfter
            val neededDelta = bgBefore - targetBg

            val rawBasalWeight = if (abs(neededDelta) < 3.0) 1.0 else (neededDelta / actualDelta.coerceAtLeast(1.0))
            val adjustedBasalWeight = 1.0 + (rawBasalWeight - 1.0) * 0.5
            val idealScale = (currentScale * adjustedBasalWeight).coerceIn(0.7, 1.5)

            val t3cWeight = if (abs(neededDelta) < 5.0) 1.0 else (neededDelta / actualDelta.coerceAtLeast(1.0))
            val idealAgg = (currentAgg * t3cWeight).coerceIn(0.5, 2.0)

            inputs.add(inputFeatures)
            basalTargets.add(doubleArrayOf(idealScale))
            t3cTargets.add(doubleArrayOf(idealAgg))
        }

        if (inputs.isEmpty()) return null
        return BasalMlDataset(inputs, basalTargets, t3cTargets)
    }
}
