package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.StringKey
import javax.inject.Inject

data class MealModeParams(
    val maxTempBasal: Double,
    val prebolus1Units: Double,
    val prebolus2Units: Double,
    val reactivity: Double,
    val smbIntervalMinutes: Int,
    val durationMinutes: Int,
)

class MealModeRepository @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
) {

    fun setActive(mode: String, params: MealModeParams) {
        val timestamp = System.currentTimeMillis()

        preferences.put(StringKey.AIMI_MEAL_MODE_ACTIVE, mode)
        preferences.put(DoubleKey.meal_modes_MaxBasal, params.maxTempBasal)
        preferences.put(DoubleKey.AIMI_MEAL_PREBOLUS1_U, params.prebolus1Units)
        preferences.put(DoubleKey.AIMI_MEAL_PREBOLUS2_U, params.prebolus2Units)
        preferences.put(DoubleKey.AIMI_MEAL_REACTIVITY, params.reactivity)
        preferences.put(IntKey.AIMI_MEAL_SMB_INTERVAL_MIN, params.smbIntervalMinutes)
        preferences.put(IntKey.AIMI_MEAL_DURATION_MIN, params.durationMinutes)
        preferences.put(LongKey.AIMI_MEAL_STARTED_AT_MS, timestamp)
    }

    fun clearActive() {
        val activeMode = preferences.get(StringKey.AIMI_MEAL_MODE_ACTIVE)
        if (activeMode.isNotEmpty()) {
            persistenceLayer.deleteLastEventMatchingKeyword(activeMode)
        }

        preferences.put(StringKey.AIMI_MEAL_MODE_ACTIVE, StringKey.AIMI_MEAL_MODE_ACTIVE.defaultValue)
        preferences.put(DoubleKey.meal_modes_MaxBasal, DoubleKey.meal_modes_MaxBasal.defaultValue)
        preferences.put(DoubleKey.AIMI_MEAL_PREBOLUS1_U, DoubleKey.AIMI_MEAL_PREBOLUS1_U.defaultValue)
        preferences.put(DoubleKey.AIMI_MEAL_PREBOLUS2_U, DoubleKey.AIMI_MEAL_PREBOLUS2_U.defaultValue)
        preferences.put(DoubleKey.AIMI_MEAL_REACTIVITY, DoubleKey.AIMI_MEAL_REACTIVITY.defaultValue)
        preferences.put(IntKey.AIMI_MEAL_SMB_INTERVAL_MIN, IntKey.AIMI_MEAL_SMB_INTERVAL_MIN.defaultValue)
        preferences.put(IntKey.AIMI_MEAL_DURATION_MIN, IntKey.AIMI_MEAL_DURATION_MIN.defaultValue)
        preferences.put(LongKey.AIMI_MEAL_STARTED_AT_MS, LongKey.AIMI_MEAL_STARTED_AT_MS.defaultValue)
    }
}