package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.workflow.LoggingWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers

/**
 * Periodic worker (6h, idle + charging) for basal / T3C neural weight training.
 */
@HiltWorker
class BasalMlTrainerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
) : LoggingWorker(appContext, workerParams, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    override suspend fun doWorkAndLog(): Result {
        if (BasalMlTrainingCoordinator.instance == null) {
            aapsLogger.warn(LTag.APS, "BasalMlTrainerWorker: coordinator not initialized — retry")
            return Result.retry()
        }
        aapsLogger.debug(LTag.APS, "BasalMlTrainerWorker: starting coordinated training")
        return runBasalMlTrainingJob()
    }
}
