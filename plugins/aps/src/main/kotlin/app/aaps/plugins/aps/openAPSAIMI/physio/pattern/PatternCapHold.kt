package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

/**
 * Short hysteresis for the physiological pattern SMB cap ([PhysiologicalPatternSnapshot.smbCapU]).
 *
 * Pattern classification can flap between consecutive loop ticks (e.g. SEDENTARY_DAY toggling on
 * `activityReduced` / steps churn). Without a hold, the cap drops to null mid rise and the HTR
 * min-cap branch in DetermineBasalAIMI2 can be skipped entirely for that tick. This holder keeps
 * the last known cap alive for [holdTicks] ticks while BG is still rising, so cap coverage stays
 * continuous within the same rise episode. The hold clears as soon as BG stops rising.
 */
class PatternCapHold(private val holdTicks: Int = DEFAULT_HOLD_TICKS) {

    private var heldCapU: Double? = null
    private var ticksLeft: Int = 0

    /** True when the last [resolve] returned a held (carried-over) value rather than a live one. */
    var holding: Boolean = false
        private set

    /**
     * @param rawCapU live cap from the current pattern snapshot (null when no active pattern defines one)
     * @param rising true while the current rise episode continues (delta > 0)
     * @return the cap to apply this tick, or null when there is genuinely nothing to hold
     */
    fun resolve(rawCapU: Double?, rising: Boolean): Double? {
        if (rawCapU != null) {
            heldCapU = rawCapU
            ticksLeft = holdTicks
            holding = false
            return rawCapU
        }
        if (!rising || ticksLeft <= 0) {
            clear()
            return null
        }
        ticksLeft--
        holding = true
        return heldCapU
    }

    private fun clear() {
        heldCapU = null
        ticksLeft = 0
        holding = false
    }

    companion object {
        const val DEFAULT_HOLD_TICKS = 3
    }
}
