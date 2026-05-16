package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central WorkManager scheduling for AIMI on-device ML trainers.
 *
 * - Basal / T3C: every 6h (idle + charging)
 * - Autodrive attention: 24h via [app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainer]
 */
@Singleton
class AimiMlTrainingScheduler @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
) {

    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()

        val basalRequest = PeriodicWorkRequestBuilder<BasalMlTrainerWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        try {
            val wm = WorkManager.getInstance(context)
            // Legacy duplicate Autodrive 6h work (superseded by 24h AutodriveNeuralTrainer schedule)
            wm.cancelUniqueWork(LEGACY_AUTODRIVE_6H_WORK)
            wm.enqueueUniquePeriodicWork(
                WORK_BASAL_ML,
                ExistingPeriodicWorkPolicy.KEEP,
                basalRequest,
            )
            aapsLogger.info(LTag.APS, "AimiMlTrainingScheduler: basal/T3C trainer scheduled (6h)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AimiMlTrainingScheduler: schedule failed", e)
        }
    }

    fun cancel() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_BASAL_ML)
            aapsLogger.info(LTag.APS, "AimiMlTrainingScheduler: basal/T3C trainer cancelled")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AimiMlTrainingScheduler: cancel failed", e)
        }
    }

    companion object {
        const val WORK_BASAL_ML = "AIMI_BASAL_ML_TRAINER"
        private const val LEGACY_AUTODRIVE_6H_WORK = "AIMINeuralTrainer"
    }
}
