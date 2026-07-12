package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import java.io.File

/**
 * Shared "train a candidate → validate → atomically publish" core for the AIMI on-device trainers, so the SMB and
 * basal/T3C heads run one training/validation/publish protocol. They differ only in feature schema, label, and
 * publish strictness — expressed as parameters here — not in the mechanics. Persistence goes through
 * [AimiNeuralModelStore].
 */
internal object NeuralModelTrainer {

    data class Split(
        val trainInputs: List<FloatArray>,
        val trainTargets: List<DoubleArray>,
        val valInputs: List<FloatArray>,
        val valTargets: List<DoubleArray>,
    )

    /** Chronology-preserving 80/20 split; if either side is empty it falls back to the train set (never empty). */
    fun split80_20(inputs: List<FloatArray>, targets: List<DoubleArray>): Split {
        val splitIdx = (inputs.size * 0.8).toInt().coerceAtLeast(1)
        val trainInputs = inputs.subList(0, splitIdx)
        val trainTargets = targets.subList(0, splitIdx)
        val valInputs = inputs.subList(splitIdx, inputs.size).takeIf { it.isNotEmpty() } ?: trainInputs
        val valTargets = targets.subList(splitIdx, targets.size).takeIf { it.isNotEmpty() } ?: trainTargets
        return Split(trainInputs, trainTargets, valInputs, valTargets)
    }

    /**
     * Train a fresh candidate on [split], validate it, and publish to [weightsFile] on success.
     *
     * @param outputRange if non-null, the zero-probe output must fall inside it (rejects degenerate models).
     * @param requireIncumbentBeat if true, the candidate's best validation loss must not exceed the incumbent's by
     *   more than [valLossTolerance] (keep the better model); if false, any finite / in-range candidate publishes.
     * @param log optional diagnostic sink (reject reasons + publish); defaults to no-op.
     * @return the published network, or null if nothing was published (caller keeps the incumbent).
     */
    fun trainAndPublish(
        weightsFile: File,
        split: Split,
        config: TrainingConfig,
        inputSize: Int,
        hiddenSize: Int = 8,
        regularizationLambda: Double = 0.01,
        outputRange: ClosedFloatingPointRange<Double>? = null,
        requireIncumbentBeat: Boolean = false,
        valLossTolerance: Double = 1.0,
        log: (String) -> Unit = {},
    ): AimiNeuralNetwork? {
        if (split.trainInputs.isEmpty() || split.valInputs.isEmpty()) return null

        val incumbent = if (requireIncumbentBeat) AimiNeuralModelStore.load(weightsFile, inputSize) else null
        val incumbentLoss = incumbent?.validate(split.valInputs, split.valTargets) ?: Double.MAX_VALUE

        val candidate = AimiNeuralNetwork(inputSize, hiddenSize, 1, config, regularizationLambda)
        candidate.trainWithValidation(split.trainInputs, split.trainTargets, split.valInputs, split.valTargets)

        val probeOut = candidate.predict(FloatArray(inputSize) { 0f })
        if (!probeOut.all { it.isFinite() }) {
            log("candidate probe NaN/Inf — discard")
            return null
        }
        val probeVal = probeOut.first().toDouble()
        if (outputRange != null && probeVal !in outputRange) {
            log("candidate probe $probeVal outside $outputRange — discard")
            return null
        }

        if (requireIncumbentBeat) {
            val candidateLoss = candidate.lastBestValidationLoss()
            if (!candidateLoss.isFinite()) return null
            val maxAllowed = incumbentLoss * valLossTolerance + 1e-6
            if (incumbent != null && candidateLoss > maxAllowed) {
                log("reject ${weightsFile.name} val=$candidateLoss > incumbent=$incumbentLoss (tol=$valLossTolerance)")
                return null
            }
        }

        if (!AimiNeuralModelStore.save(weightsFile, candidate)) {
            log("atomic save failed for ${weightsFile.name}")
            return null
        }
        log("published ${weightsFile.name}")
        return candidate
    }
}
