package app.aaps.core.data.afrezza

/**
 * Shared runtime state for the Afrezza "max basal" feature.
 *
 * Lives in :core:data so that both the UI (Afrezza dialog, which activates it) and the APS
 * constraint code (which enforces it) can reference the same instance without a module dependency cycle.
 */
object AfrezzaMaxBasalState {
    @Volatile var endTime: Long = 0L
    @Volatile var rate: Double = 2.0
    @Volatile var cobZeroSince: Long = 0L
    @Volatile var activatedAt: Long = 0L
    val isActive: Boolean get() = endTime > System.currentTimeMillis()
    val remainingMinutes: Int get() = if (isActive) ((endTime - System.currentTimeMillis()) / 60_000L).toInt() else 0

    fun activate(rateUh: Double, durationMinutes: Int) {
        rate = rateUh
        cobZeroSince = 0L
        activatedAt = System.currentTimeMillis()
        endTime = System.currentTimeMillis() + (durationMinutes * 60_000L)
    }

    fun cancel() {
        endTime = 0L
        cobZeroSince = 0L
    }
}
