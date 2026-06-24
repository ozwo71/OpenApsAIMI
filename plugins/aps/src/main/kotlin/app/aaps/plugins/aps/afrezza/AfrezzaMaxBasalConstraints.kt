package app.aaps.plugins.aps.afrezza

import app.aaps.core.data.afrezza.AfrezzaMaxBasalState
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.runBlocking

/**
 * Enforces a temporary elevated basal floor after an Afrezza dose (hypo guard, COB / extended-carb awareness).
 */
object AfrezzaMaxBasalConstraints {

    fun apply(
        absoluteRate: Constraint<Double>,
        from: Any,
        iobCobCalculator: IobCobCalculator,
        persistenceLayer: PersistenceLayer,
        aapsLogger: AAPSLogger,
    ): Constraint<Double> {
        if (!AfrezzaMaxBasalState.isActive) return absoluteRate

        val currentBg = iobCobCalculator.ads.actualBg()?.recalculated ?: 0.0
        if (currentBg in 1.0..70.0) {
            aapsLogger.info(LTag.APS, "Afrezza max basal paused — BG is $currentBg mg/dL (hypo guard)")
            return absoluteRate
        }

        val lastAutosens = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("Afrezza constraint")
        val cob = lastAutosens?.cob ?: 0.0
        if (cob <= 0.0) {
            val hasActiveExtendedCarbs = runBlocking {
                val recentCarbs = persistenceLayer.getCarbsFromTime(
                    AfrezzaMaxBasalState.activatedAt - 30 * 60_000L, true
                )
                recentCarbs.any { it.duration > 0 && (it.timestamp + it.duration) > System.currentTimeMillis() }
            }
            if (hasActiveExtendedCarbs) {
                AfrezzaMaxBasalState.cobZeroSince = 0L
                aapsLogger.info(LTag.APS, "Afrezza max basal — COB=0 but extended carbs active, continuing")
            } else {
                if (AfrezzaMaxBasalState.cobZeroSince == 0L) {
                    AfrezzaMaxBasalState.cobZeroSince = System.currentTimeMillis()
                    aapsLogger.info(LTag.APS, "Afrezza max basal — COB hit 0, bread carbs absorbing")
                } else if (System.currentTimeMillis() - AfrezzaMaxBasalState.cobZeroSince > 5 * 60_000L) {
                    aapsLogger.info(LTag.APS, "Afrezza max basal stopped — bread carbs absorbed")
                    AfrezzaMaxBasalState.cancel()
                    return absoluteRate
                }
            }
        } else {
            AfrezzaMaxBasalState.cobZeroSince = 0L
        }

        absoluteRate.setIfGreater(AfrezzaMaxBasalState.rate, "Afrezza max basal active", from)
        return absoluteRate
    }
}
