package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbRefinementFeatureSchema
import app.aaps.plugins.aps.openAPSAIMI.ml.TrainingCircuitBreaker
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
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
    private val basalNeuralLearner: BasalNeuralLearner,
    private val log: AAPSLogger,
) {

    companion object {
        private const val TAG = "BasalMlTraining"

        // 6 glucose-dynamics base features + 10 physiological-context features (mirror of the SMB schema:
        // 4 latent + 3 patient-mode + 3 causal). Keep in sync with BasalNeuralLearner.modelInput / the parser.
        const val BASE_FEATURE_COUNT = 6
        const val INPUT_SIZE = 16
        private const val TRAIN_INTERVAL_MS = 1L * 60 * 60 * 1000 // 1h — matches the 1h worker cadence; the MIN_NEW_ROWS gate still prevents retraining without new data
        private const val MIN_NEW_ROWS = 80L
        private const val BASAL_MIN_ROWS = 100
        private const val T3C_MIN_ROWS = 50
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
    private val trainScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastTrainMs = AtomicLong(0L)
    private val rowsAtLastTrain = AtomicLong(0L)
    private val circuitBreaker = TrainingCircuitBreaker()

    init {
        instance = this
        loadPersistedState()
    }

    /**
     * Fire-and-forget training trigger (SMB-style): called from the loop hot path after each basal record.
     * Never blocks the caller; [runScheduledTraining] enforces rate limit, min new rows, and mutex.
     */
    fun maybeTrainAsync() {
        trainScope.launch {
            if (trainMutex.isLocked) return@launch
            try {
                runScheduledTraining()
            } catch (e: Exception) {
                log.error(LTag.APS, "$TAG: maybeTrainAsync failed", e)
            }
        }
    }

    suspend fun runScheduledTraining(): TrainingOutcome = trainMutex.withLock {
        val now = System.currentTimeMillis()
        // First-ever model creation bypasses the rate limit: if no basal weights exist yet, train now (bootstrap) so
        // the model is created ASAP from the already-accumulated CSV, instead of waiting for the next 6h window.
        val bootstrapNeeded = !storageHelper.getAimiFile(BASAL_WEIGHTS).exists()
        if (isCircuitOpen(now)) {
            log.debug(LTag.APS, "$TAG: circuit breaker open — skip")
            return TrainingOutcome.SKIPPED
        }
        if (!bootstrapNeeded && now - lastTrainMs.get() < TRAIN_INTERVAL_MS) {
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

        // Training is DECOUPLED from usage: both heads are trained whenever enough recorded data exists, so the
        // weights files (basal_adaptive_weights.json / t3c_brain_weights.json) are always produced and kept fresh.
        // The feature prefs (OApsAIMIT3cAdaptiveBasalEnabled / OApsAIMIT3cBrittleMode) gate only whether
        // BasalNeuralLearner APPLIES the models at runtime — never whether they are trained. This guarantees a model
        // is ready the instant the feature is enabled, instead of starting from an empty model. Recording is likewise
        // pref-independent (BasalNeuralLearner.logRecord), so the dataset keeps growing for everyone.
        return try {
            var published = false
            var hardFailure = false

            if (parsed.rowCount >= BASAL_MIN_ROWS) {
                when (trainBasalHead(parsed)) {
                    HeadTrainResult.PUBLISHED -> published = true
                    HeadTrainResult.FAILED -> hardFailure = true
                    HeadTrainResult.SKIPPED -> Unit
                }
            }

            if (parsed.rowCount >= T3C_MIN_ROWS) {
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
                    markAttemptCompleted(now, totalRows)
                    circuitBreaker.reset()
                    basalNeuralLearner.reloadModels()
                    log.info(LTag.APS, "$TAG: training complete — models reloaded ($totalRows CSV rows)")
                    TrainingOutcome.SUCCESS
                }
                else -> {
                    // An attempt that ran to completion without beating the incumbent still counts.
                    //
                    // The rate limit and the min-new-rows gate both keyed on the last *publish*. Once a
                    // model stopped being beaten, `newRows` kept growing past the threshold and nothing
                    // ever reset the clock — so a full 200+300-epoch training over the whole CSV ran on
                    // every tick, forever, on a phone.
                    markAttemptCompleted(now, totalRows)
                    log.debug(LTag.APS, "$TAG: candidate did not beat the incumbent — attempt recorded")
                    TrainingOutcome.SKIPPED
                }
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

    // Basal/T3C publish strictly: the candidate must beat the incumbent within [VAL_LOSS_TOLERANCE] and probe inside
    // [outputRange]. Shared training/validation/publish mechanics live in [NeuralModelTrainer].
    private fun trainAndMaybePublish(
        weightsFile: File,
        trainInputs: List<FloatArray>,
        trainTargets: List<DoubleArray>,
        valInputs: List<FloatArray>,
        valTargets: List<DoubleArray>,
        config: TrainingConfig,
        outputRange: ClosedFloatingPointRange<Double>,
    ): Boolean {
        val hasIncumbent = weightsFile.exists()
        return NeuralModelTrainer.trainAndPublish(
            weightsFile = weightsFile,
            split = NeuralModelTrainer.Split(trainInputs, trainTargets, valInputs, valTargets),
            config = config,
            inputSize = INPUT_SIZE,
            probeInput = BasalMlDatasetParser.representativeProbeInput(trainInputs),
            // Bootstrap: publish any finite candidate (SMB-style). Retrain: probe + range + beat incumbent.
            outputRange = if (hasIncumbent) outputRange else null,
            requireIncumbentBeat = hasIncumbent,
            valLossTolerance = VAL_LOSS_TOLERANCE,
            log = { log.info(LTag.APS, "$TAG: $it") },
        ) != null
    }

    private fun isCircuitOpen(now: Long): Boolean = circuitBreaker.isOpen(now)

    private fun recordFailure() {
        if (circuitBreaker.recordFailure()) {
            log.warn(LTag.APS, "$TAG: circuit breaker OPEN for 6h after ${TrainingCircuitBreaker.DEFAULT_MAX_FAILURES} failures")
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

    /**
     * Records that a training attempt ran to completion, whether or not it published a model.
     *
     * Both gates — the interval in [TRAIN_INTERVAL_MS] and [MIN_NEW_ROWS] — key on this. Recording it
     * only on publish meant a model that stopped being beaten left the clock frozen while the row
     * count kept growing, so the coordinator retrained on every tick indefinitely.
     */
    private fun markAttemptCompleted(nowMs: Long, totalRows: Long) {
        lastTrainMs.set(nowMs)
        rowsAtLastTrain.set(totalRows)
        persistState()
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

    /** Feature centroid for publish-time probe (replaces all-zero vector that never appears in training data). */
    fun representativeProbeInput(trainInputs: List<FloatArray>): FloatArray {
        if (trainInputs.isEmpty()) return neutralProbeInput()
        val size = trainInputs.first().size
        val acc = FloatArray(size)
        var used = 0
        for (row in trainInputs) {
            if (row.size != size) continue
            for (i in row.indices) acc[i] += row[i]
            used++
        }
        if (used == 0) return neutralProbeInput()
        val n = used.toFloat()
        for (i in acc.indices) acc[i] /= n
        return acc
    }

    /** Fallback probe when the train set is empty (should not happen in normal publish flow). */
    fun neutralProbeInput(): FloatArray {
        val base = floatArrayOf(100f, 1f, 0f, 30f, 45f, 0.5f)
        val physio = SmbRefinementFeatureSchema.latentFeatureValues(null) +
            SmbRefinementFeatureSchema.modeFeatureValues(null) +
            SmbRefinementFeatureSchema.causalFeatureValues(null)
        return base + physio
    }

    // 🩸 Alignement temporel du label. La cible d'apprentissage doit être le résultat RÉELLEMENT observé, donc
    // le BG mesuré ~30 min après le tick (colonne `bg` d'une ligne future), et NON la colonne `eventualBg` —
    // qui est une prédiction pkpd plancherée à 39 mg/dL (≈24 % des lignes) et produisait des labels fictifs
    // « crash à 39 » sur des BG réels normaux. On joint donc chaque ligne à son futur par timestamp.
    private const val LABEL_HORIZON_MS = 30L * 60_000        // cible : BG réalisé à +30 min
    private const val LABEL_HORIZON_MIN_MS = 20L * 60_000    // borne basse de la fenêtre d'acceptation
    private const val LABEL_HORIZON_MAX_MS = 45L * 60_000    // borne haute (au-delà = trop décorrélé)
    private const val MIN_VALID_BG = 40.0                    // en dessous = plancher/glitch capteur → ligne écartée
    private const val MAX_VALID_BG = 400.0
    private const val MAX_PLAUSIBLE_DELTA_MGDL = 150.0       // |bg − bgFutur| au-delà = glitch → écarté

    private class RawRow(
        val ts: Long,
        val bg: Double,
        val target: Double,
        val currentScale: Double,
        val currentAgg: Double,
        val features: FloatArray,
    )

    fun parse(csvFile: File): BasalMlDataset? {
        val allLines = csvFile.readLines()
        if (allLines.size < 2) return null

        val header = allLines.first().split(",")
        val iTs = header.indexOf("timestamp")
        val iBg = header.indexOf("bg")
        val iBasal = header.indexOf("basal")
        val iTarget = header.indexOf("target")
        val iAccel = header.indexOf("accel")
        val iDuraMin = header.indexOf("duraMin")
        val iDuraAvg = header.indexOf("duraAvg")
        val iIob = header.indexOf("iob")
        val iBasalScale = header.indexOf("basalScale")
        val iT3cAgg = header.indexOf("t3cAgg")

        val required = listOf(iTs, iBg, iBasal, iTarget, iAccel, iDuraMin, iDuraAvg, iIob, iBasalScale, iT3cAgg)
        if (required.any { it < 0 }) return null
        val requiredMaxIdx = required.max()

        // Physio-context columns (mirror of the SMB schema). Read by name; absent column OR short (legacy) row →
        // neutral value → schema versioning + neutral backfill so the pre-physio history keeps training.
        val physioNames = SmbRefinementFeatureSchema.latentFeatureNames +
            SmbRefinementFeatureSchema.modeFeatureNames +
            SmbRefinementFeatureSchema.causalFeatureNames
        val neutralPhysio = SmbRefinementFeatureSchema.latentFeatureValues(null) +
            SmbRefinementFeatureSchema.modeFeatureValues(null) +
            SmbRefinementFeatureSchema.causalFeatureValues(null)
        val physioIdx = physioNames.map { header.indexOf(it) }

        // 1) Parse + filtre de validité (BG plausible), puis tri chronologique pour la jointure du label.
        val raw = ArrayList<RawRow>(allLines.size)
        for (line in allLines.drop(1)) {
            val cols = line.split(",")
            // Require only the base columns (physio columns are optional → neutral backfill for legacy rows).
            if (cols.size <= requiredMaxIdx) continue
            val ts = cols[iTs].toLongOrNull() ?: continue
            val bg = cols[iBg].toDoubleOrNull() ?: continue
            if (!bg.isFinite() || bg < MIN_VALID_BG || bg > MAX_VALID_BG) continue
            val basal = cols[iBasal].toFloatOrNull() ?: continue
            val accel = cols[iAccel].toFloatOrNull() ?: continue
            val duraMin = cols[iDuraMin].toFloatOrNull() ?: continue
            val duraAvg = cols[iDuraAvg].toFloatOrNull() ?: continue
            val iob = cols[iIob].toFloatOrNull() ?: continue
            val target = cols[iTarget].toDoubleOrNull() ?: continue
            val currentScale = cols[iBasalScale].toDoubleOrNull() ?: continue
            val currentAgg = cols[iT3cAgg].toDoubleOrNull() ?: continue
            val physio = FloatArray(physioNames.size) { j ->
                val idx = physioIdx[j]
                if (idx >= 0) cols.getOrNull(idx)?.toFloatOrNull() ?: neutralPhysio[j] else neutralPhysio[j]
            }
            raw.add(
                RawRow(
                    ts = ts,
                    bg = bg,
                    target = target,
                    currentScale = currentScale,
                    currentAgg = currentAgg,
                    features = floatArrayOf(bg.toFloat(), basal, accel, duraMin, duraAvg, iob) + physio,
                )
            )
        }
        if (raw.size < 2) return null
        raw.sortBy { it.ts }

        val inputs = mutableListOf<FloatArray>()
        val basalTargets = mutableListOf<DoubleArray>()
        val t3cTargets = mutableListOf<DoubleArray>()

        // 2) Cible = BG réalisé (vérité terrain) au lieu de la prédiction. Les lignes sans futur observable
        //    (fin de log / trou de données) sont écartées — on ne fabrique pas de label.
        for (i in raw.indices) {
            val r = raw[i]
            val realizedBg = realizedFutureBg(raw, i) ?: continue
            val actualDelta = r.bg - realizedBg
            if (abs(actualDelta) > MAX_PLAUSIBLE_DELTA_MGDL) continue
            val neededDelta = r.bg - r.target

            val rawBasalWeight = if (abs(neededDelta) < 3.0) 1.0 else (neededDelta / actualDelta.coerceAtLeast(1.0))
            val adjustedBasalWeight = 1.0 + (rawBasalWeight - 1.0) * 0.5
            val idealScale = (r.currentScale * adjustedBasalWeight).coerceIn(0.7, 1.5)

            val t3cWeight = if (abs(neededDelta) < 5.0) 1.0 else (neededDelta / actualDelta.coerceAtLeast(1.0))
            val idealAgg = (r.currentAgg * t3cWeight).coerceIn(0.5, 2.0)

            inputs.add(r.features)
            basalTargets.add(doubleArrayOf(idealScale))
            t3cTargets.add(doubleArrayOf(idealAgg))
        }

        if (inputs.isEmpty()) return null
        return BasalMlDataset(inputs, basalTargets, t3cTargets)
    }

    /**
     * BG réellement mesuré ~[LABEL_HORIZON_MS] après la ligne [i] : ligne future dont le timestamp est le plus
     * proche de la cible, dans la fenêtre [[LABEL_HORIZON_MIN_MS], [LABEL_HORIZON_MAX_MS]]. `null` si aucune
     * (fin du log ou trou de données) → la ligne ne peut pas être labellisée. [rows] doit être trié par ts.
     */
    private fun realizedFutureBg(rows: List<RawRow>, i: Int): Double? {
        val base = rows[i].ts
        val targetTs = base + LABEL_HORIZON_MS
        val minTs = base + LABEL_HORIZON_MIN_MS
        val maxTs = base + LABEL_HORIZON_MAX_MS
        var best: Double? = null
        var bestDiff = Long.MAX_VALUE
        for (j in i + 1 until rows.size) {
            val ts = rows[j].ts
            if (ts < minTs) continue
            if (ts > maxTs) break
            val diff = abs(ts - targetTs)
            if (diff < bestDiff) {
                bestDiff = diff
                best = rows[j].bg
            }
        }
        return best
    }
}
