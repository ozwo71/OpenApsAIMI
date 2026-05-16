package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import java.io.File

/**
 * Crash-safe persistence for basal / T3C neural weight files.
 * Write: tmp → rotate main to .bak → atomic rename to main.
 */
internal object BasalMlModelStore {

    private fun tmpFile(target: File) = File(target.parentFile, "${target.name}.tmp")
    private fun bakFile(target: File) = File(target.parentFile, "${target.name}.bak")

    fun saveAtomic(target: File, network: AimiNeuralNetwork): Boolean {
        return try {
            target.parentFile?.mkdirs()
            val tmp = tmpFile(target)
            val bak = bakFile(target)
            network.saveToFile(tmp)
            if (target.exists()) {
                bak.delete()
                target.renameTo(bak)
            }
            tmp.renameTo(target)
        } catch (_: Exception) {
            false
        }
    }

    fun loadValid(target: File, expectedInputSize: Int): AimiNeuralNetwork? {
        for (file in listOf(target, bakFile(target))) {
            if (!file.exists()) continue
            try {
                val net = AimiNeuralNetwork.loadFromFile(file) ?: continue
                val probe = FloatArray(expectedInputSize) { 0f }
                val out = net.predict(probe)
                if (out.all { it.isFinite() }) return net
            } catch (_: Exception) {
                // try backup
            }
        }
        return null
    }
}
