package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central WorkManager scheduling for AIMI on-device ML trainers.
 *
 * - Basal / T3C: every 1h, no constraints (runs regardless of charging / device-idle / battery) + one-time bootstrap
 * - Autodrive attention: 24h via [app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainer]
 */
@Singleton
class AimiMlTrainingScheduler @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper,
) {

    fun schedule() {
        // Runs every 1h with NO constraints — must execute regardless of charging / device-idle / battery. The old
        // setRequiresCharging + setRequiresDeviceIdle combo almost never coincided on real phones, so the worker never
        // fired and the weights were never produced. WorkManager may still defer during deep Doze to a maintenance
        // window, but there is no longer any charging/idle *requirement*. Actual retrains stay bounded by the
        // coordinator's rate limit + min-new-rows gate, so a 1h cadence is not wasteful.
        val basalRequest = PeriodicWorkRequestBuilder<BasalMlTrainerWorker>(1, TimeUnit.HOURS)
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
            // Bootstrap: one immediate pass so the FIRST model is created ASAP when enough CSV data already exists.
            // REPLACE (not KEEP) while weights are still missing — a prior failed bootstrap must not block retries.
            val bootstrapNeeded =
                !storageHelper.getAimiFile(BASAL_WEIGHTS).exists() ||
                    !storageHelper.getAimiFile(T3C_WEIGHTS).exists()
            val bootstrapPolicy = if (bootstrapNeeded) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            val bootstrapRequest = OneTimeWorkRequestBuilder<BasalMlTrainerWorker>()
                .build()
            wm.enqueueUniqueWork(WORK_BASAL_ML_BOOTSTRAP, bootstrapPolicy, bootstrapRequest)
            aapsLogger.info(
                LTag.APS,
                "AimiMlTrainingScheduler: basal/T3C trainer scheduled (1h, no constraints) + bootstrap " +
                    "enqueued (policy=$bootstrapPolicy, needed=$bootstrapNeeded)",
            )
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
        private const val BASAL_WEIGHTS = "basal_adaptive_weights.json"
        private const val T3C_WEIGHTS = "t3c_brain_weights.json"
    }
}
