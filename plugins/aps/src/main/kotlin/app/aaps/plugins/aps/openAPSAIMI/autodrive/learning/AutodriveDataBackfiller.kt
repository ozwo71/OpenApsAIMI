package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🧹 Autodrive Data Backfiller
 * 
 * Sa mission : Rouvrir le fichier `autodrive_dataset.csv` et compléter les colonnes laissées vides
 * lors de la collecte initiale (Future_BG_45m, Hypo_Occurred, Hyper_Occurred).
 * Il regarde dans le "futur" (lignes suivantes du fichier) pour évaluer la décision passée.
 */
@Singleton
class AutodriveDataBackfiller @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper,
    private val persistenceLayer: PersistenceLayer,
) {
    companion object {
        @Volatile
        var instance: AutodriveDataBackfiller? = null
            internal set
    }

    init {
        // Scheduling lives in AimiMlTrainingScheduler, which the plugin starts and stops. Enqueuing
        // from a constructor fires at DI graph construction, in no defined order, and the constraints
        // used here (charging + device idle) almost never coincide on a real phone — the same reason
        // the basal trainer had to drop them.
        instance = this
    }


    private val csvFileName = "autodrive_dataset.csv"
    private val tmpCsvFileName = "autodrive_dataset_tmp.csv"

    // Index des colonnes (basé sur AutodriveDataLake.kt)
    private val IDX_TIMESTAMP = 0
    private val IDX_BG = 2
    private val IDX_FUTURE_BG = 15
    private val IDX_HYPO = 16
    private val IDX_HYPER = 17

    /** Fenêtre d'historique conservée dans le CSV d'entraînement. */
    private val RETENTION_DAYS = 60L
    private val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1000L
    private val MIN_MILLIS_FOR_FUTURE = 45 * 60 * 1000L // 45 minutes
    private val MAX_MILLIS_FOR_HYPO = 60 * 60 * 1000L   // 60 minutes de fenêtre

    /**
     * Parse le fichier CSV, trouve les lignes incomplètes (récentes il y a > 45min),
     * les complète en lisant les glycémies futures, et réécrit le fichier proprement.
     * 
     * @return Le nombre de lignes back-fillées avec succès.
     */
    /**
     * Read-modify-rename over the whole dataset; held under [AutodriveDatasetLock] as one transaction.
     *
     * Outcome labels come from the **CGM history**, not from the rows of this CSV. Rows are only
     * written on ticks where Autodrive engaged, and a hypoglycaemia is precisely what makes Autodrive
     * disengage — so labelling from the CSV censored the positive class exactly where it matters, and
     * silently wrote `0`.
     */
    suspend fun processPendingLines(): Int {
        val readings = loadGlucoseWindow()
        return AutodriveDatasetLock.withDataset { processPendingLinesLocked(readings) }
    }

    /** CGM readings covering every pending row's outcome window, read once rather than per row. */
    private suspend fun loadGlucoseWindow(): List<Pair<Long, Double>> = try {
        val file = storageHelper.getAimiFile(csvFileName)
        if (!file.exists()) {
            emptyList()
        } else {
            val stamps = file.useLines { lines ->
                lines.drop(1).mapNotNull { it.split(",").getOrNull(IDX_TIMESTAMP)?.toLongOrNull() }
                    .filter { it > 0L }
                    .toList()
            }
            if (stamps.isEmpty()) {
                emptyList()
            } else {
                persistenceLayer
                    .getBgReadingsDataFromTimeToTime(stamps.min(), stamps.max() + MAX_MILLIS_FOR_HYPO, true)
                    .map { it.timestamp to it.value }
            }
        }
    } catch (e: Exception) {
        aapsLogger.error(LTag.APS, "Backfill: glucose history unavailable — ${e.message}")
        emptyList()
    }

    private fun processPendingLinesLocked(readings: List<Pair<Long, Double>>): Int {
        val originalFile = storageHelper.getAimiFile(csvFileName)
        if (!originalFile.exists()) return 0

        val lines = try {
            originalFile.readLines().toMutableList()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Backfiller Error reading CSV: ${e.message}")
            return 0
        }

        if (lines.size <= 1) return 0 // Seulement le header

        val header = lines[0]
        var modifiedCount = 0

        // Parse lines for quick access
        val parsedLines = lines.drop(1).mapNotNull { line ->
            val cols = line.split(",")
            if (cols.size > IDX_HYPER) {
                val timestamp = cols[IDX_TIMESTAMP].toLongOrNull() ?: 0L
                val bg = cols[IDX_BG].toDoubleOrNull() ?: 0.0
                ParsedRow(cols.toMutableList(), timestamp, bg)
            } else null
        }

        // Parcourt les lignes du début à la fin
        for (i in parsedLines.indices) {
            val currentRow = parsedLines[i]
            val timestampNow = currentRow.timestamp
            val futureBgStr = currentRow.cols[IDX_FUTURE_BG]

            // Si la ligne n'a pas encore son 'Reward' validé
            if (futureBgStr.isBlank() && timestampNow > 0) {
                
                // Recherche dans le futur (lignes i+1 à EOF)
                val targetMillis = timestampNow + MIN_MILLIS_FOR_FUTURE
                val maxWindowMillis = timestampNow + MAX_MILLIS_FOR_HYPO
                
                var futureBgVal: Double? = null
                var hypoOccurred = false
                var hyperOccurred = false

                // Étiquetage depuis la glycémie réelle : continue par construction, donc un
                // décrochage d'Autodrive ne masque plus une hypo.
                val windowReadings = readings.filter { (ts, _) -> ts in (timestampNow + 1)..maxWindowMillis }
                if (windowReadings.isNotEmpty()) {
                    hypoOccurred = windowReadings.any { (_, bgV) -> bgV > 0.0 && bgV < 70.0 }
                    // Première mesure au-delà de +45 min, mais **dans** la fenêtre : sans borne haute,
                    // une valeur trois heures plus tard était estampillée comme le résultat à 45 min.
                    windowReadings.firstOrNull { (ts, _) -> ts >= targetMillis }?.let { (_, bgV) ->
                        futureBgVal = bgV
                        if (bgV >= 180.0) hyperOccurred = true
                    }
                }

                if (futureBgVal != null) {
                    currentRow.cols[IDX_FUTURE_BG] = futureBgVal.toString()
                    currentRow.cols[IDX_HYPO] = if (hypoOccurred) "1" else "0"
                    currentRow.cols[IDX_HYPER] = if (hyperOccurred) "1" else "0"
                    currentRow.isModified = true
                    modifiedCount++
                }
            }
        }

        // Rétention glissante. Le fichier n'avait aucun plafond : il est relu **intégralement** par
        // cette passe toutes les 6 h, par la porte de volume et par l'entraîneur. Enregistrer chaque
        // tick au lieu des seuls ticks engagés multiplie sa croissance par ~3,5, donc le plafond doit
        // exister avant. La purge se fait ici parce que cette passe réécrit le fichier de toute façon.
        val retentionCutoff = System.currentTimeMillis() - RETENTION_MILLIS
        val retained = parsedLines.filter { it.timestamp <= 0L || it.timestamp >= retentionCutoff }
        val prunedCount = parsedLines.size - retained.size
        if (prunedCount > 0) {
            aapsLogger.info(LTag.APS, "Backfill: pruned $prunedCount rows older than $RETENTION_DAYS days")
        }

        // Si on a complété des données ou purgé, on réécrit le fichier Atomiquement
        if (modifiedCount > 0 || prunedCount > 0) {
            val tmpFile = storageHelper.getAimiFile(tmpCsvFileName)
            try {
                tmpFile.bufferedWriter().use { writer ->
                    writer.write(header)
                    writer.newLine()
                    retained.forEach { row ->
                        writer.write(row.cols.joinToString(","))
                        writer.newLine()
                    }
                }

                // Swap atomique
                if (tmpFile.renameTo(originalFile)) {
                    aapsLogger.info(LTag.APS, "Backfiller a complété $modifiedCount lignes d'entraînement.")
                } else {
                    // Fallback rename manuel sur certains Androids
                    tmpFile.copyTo(originalFile, overwrite = true)
                    tmpFile.delete()
                }

            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Backfiller Error writing CSV: ${e.message}")
            }
        }

        return modifiedCount
    }

    /**
     * Phase 8 : Data Quality Gate (Sécurité Volumétrique)
     * Vérifie si le fichier CSV contient assez de lignes "Back-fillées" (avec Future_BG rempli)
     * pour autoriser un entraînement IA sans risque d'Overfitting.
     */
    fun isDatasetReadyForTraining(minimumValidLines: Int = 2880): Boolean {
        val file = storageHelper.getAimiFile(csvFileName)
        if (!file.exists()) return false

        return try {
            var validCount = 0
            file.useLines { lines ->
                // On passe le header
                lines.drop(1).forEach { line ->
                    val cols = line.split(",")
                    if (cols.size > IDX_FUTURE_BG && cols[IDX_FUTURE_BG].isNotBlank()) {
                        validCount++
                    }
                    // Early exit si on a atteint le quota
                    if (validCount >= minimumValidLines) return true
                }
            }
            false
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Gate Error: ${e.message}")
            false
        }
    }

    private data class ParsedRow(
        val cols: MutableList<String>,
        val timestamp: Long,
        val bg: Double,
        var isModified: Boolean = false
    )
}
