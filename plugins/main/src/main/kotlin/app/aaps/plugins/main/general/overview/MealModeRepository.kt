package app.aaps.plugins.main.general.overview

import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealModeRepository @Inject constructor(
    private val persistenceLayer: PersistenceLayer
) {

    fun setActive(
        mode: MealMode,
        params: MealParameters,
        timestamp: Long = System.currentTimeMillis()
    ): Single<PersistenceLayer.TransactionResult<TE>> {
        val therapyEvent = TE(
            timestamp = timestamp,
            type = TE.Type.NOTE
        ).apply {
            note = mode.keyword
            duration = T.mins(params.duration.toLong()).msecs()
        }

        return persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = therapyEvent,
            timestamp = timestamp,
            action = Action.CAREPORTAL,
            source = Sources.Overview,
            note = mode.keyword,
            listValues = listOf(ValueWithUnit.SimpleString(mode.keyword))
        )
    }

    private val MealMode.keyword: String
        get() = noteKeyword
}