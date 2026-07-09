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
 * Legacy worker name — delegates to [BasalMlTrainingCoordinator].
 * Prefer [BasalMlTrainerWorker] scheduled by [AimiMlTrainingScheduler].
 */
@HiltWorker
class BasalAdaptiveTrainerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    private val coordinator: BasalMlTrainingCoordinator,
) : LoggingWorker(appContext, workerParams, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    override suspend fun doWorkAndLog(): Result {
        aapsLogger.debug(LTag.APS, "BasalAdaptiveTrainerWorker: delegating to BasalMlTrainingCoordinator")
        return runBasalMlTrainingJob(coordinator)
    }
}
