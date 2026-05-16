package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers

/**
 * Periodic worker (6h, idle + charging) for basal / T3C neural weight training.
 */
class BasalMlTrainerWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : LoggingWorker(appContext, workerParams, Dispatchers.IO) {

    override suspend fun doWorkAndLog(): Result {
        if (BasalMlTrainingCoordinator.instance == null) {
            aapsLogger.warn(LTag.APS, "BasalMlTrainerWorker: coordinator not initialized — retry")
            return Result.retry()
        }
        aapsLogger.debug(LTag.APS, "BasalMlTrainerWorker: starting coordinated training")
        return runBasalMlTrainingJob()
    }
}
