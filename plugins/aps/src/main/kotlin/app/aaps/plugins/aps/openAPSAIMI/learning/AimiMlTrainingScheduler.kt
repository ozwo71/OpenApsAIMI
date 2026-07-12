package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
 * - Basal / T3C: every 6h (battery-not-low; runs regardless of charging / device-idle)
 * - Autodrive attention: 24h via [app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainer]
 */
@Singleton
class AimiMlTrainingScheduler @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
) {

    fun schedule() {
        // Train regardless of charging / device-idle. The previous setRequiresCharging + setRequiresDeviceIdle combo
        // almost never coincided on real phones, so this 6h worker rarely fired and the weights files were never
        // produced — while the loop-written CSVs/state and the loop-trained SMB model always were. Keep only a
        // battery-not-low guard (avoid heavy training on a nearly-dead battery), matching the on-loop SMB training.
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val basalRequest = PeriodicWorkRequestBuilder<BasalMlTrainerWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        try {
            val wm = WorkManager.getInstance(context)
            // Legacy duplicate Autodrive 6h work (superseded by 24h AutodriveNeuralTrainer schedule)
            wm.cancelUniqueWork(LEGACY_AUTODRIVE_6H_WORK)
            // UPDATE (not KEEP): existing installs already enqueued the old charging+idle work; UPDATE re-applies the
            // relaxed constraints while preserving the periodic schedule, so the fix reaches devices that scheduled it.
            wm.enqueueUniquePeriodicWork(
                WORK_BASAL_ML,
                ExistingPeriodicWorkPolicy.UPDATE,
                basalRequest,
            )
            // Bootstrap: one immediate pass so the FIRST model is created ASAP (no 6h/charging/idle wait) when enough
            // CSV data already exists. The coordinator no-ops it once a fresh model exists (rate limit + min-rows),
            // and bypasses the rate limit while the weights are still missing (first creation).
            val bootstrapRequest = OneTimeWorkRequestBuilder<BasalMlTrainerWorker>()
                .setConstraints(constraints)
                .build()
            wm.enqueueUniqueWork(WORK_BASAL_ML_BOOTSTRAP, ExistingWorkPolicy.KEEP, bootstrapRequest)
            aapsLogger.info(LTag.APS, "AimiMlTrainingScheduler: basal/T3C trainer scheduled (6h) + bootstrap enqueued")
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
        const val WORK_BASAL_ML_BOOTSTRAP = "AIMI_BASAL_ML_TRAINER_BOOTSTRAP"
        private const val LEGACY_AUTODRIVE_6H_WORK = "AIMINeuralTrainer"
    }
}
