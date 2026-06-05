package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences

data class RecursiveBeliefPreferences(
    val shadowEnabled: Boolean,
    val authorityEnabled: Boolean,
    val waveletEnabled: Boolean,
) {
    companion object {
        fun from(preferences: Preferences): RecursiveBeliefPreferences =
            RecursiveBeliefPreferences(
                shadowEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow),
                authorityEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority),
                waveletEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefWavelet),
            )

        /** Active when shadow or authority is on — build + export at minimum. */
        fun isActive(prefs: RecursiveBeliefPreferences): Boolean =
            prefs.shadowEnabled || prefs.authorityEnabled
    }
}
