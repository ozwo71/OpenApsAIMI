package app.aaps.plugins.source

/**
 * Pure rules behind "correct the insertion date" on the ONE+ Status screen.
 *
 * The sensor age of this source comes from the driver's own stored session start, not from the
 * `SENSOR_CHANGE` therapy event, and that clock is written once — at pairing, or at the first
 * reading. A sensor inserted hours before it was paired therefore carried a wrong age for its whole
 * life, and nothing in the app could move it: adding a `SENSOR_CHANGE` by hand does not reach this
 * clock, and an added event that is EARLIER than the one the plugin already wrote is not even the
 * "last sensor change" any more, so the dashboard ignores it too.
 */
object DexcomOnePlusSensorStartCorrection {

    /** Verdict of [validate] — the screen turns each case into its own message. */
    sealed interface Verdict {

        data object Accepted : Verdict

        /** A sensor cannot have been inserted in the future. */
        data object InFuture : Verdict

        /** Older than a full sensor life plus its grace: this would be a different sensor. */
        data object TooOld : Verdict

        /** No sensor session is stored, so there is no age to correct. */
        data object NoSession : Verdict
    }

    /**
     * @param newStartMs insertion time the user picked
     * @param currentStartMs session start stored today (0 when none)
     * @param nowMs current wall-clock time
     * @param maxAgeMs oldest insertion still plausible for the running sensor
     */
    fun validate(
        newStartMs: Long,
        currentStartMs: Long,
        nowMs: Long,
        maxAgeMs: Long = DexcomOnePlusStaging.SENSOR_LIFE_MS + DexcomOnePlusStaging.SENSOR_GRACE_MS,
    ): Verdict = when {
        currentStartMs <= 0L          -> Verdict.NoSession
        newStartMs > nowMs            -> Verdict.InFuture
        nowMs - newStartMs > maxAgeMs -> Verdict.TooOld
        else                          -> Verdict.Accepted
    }

    /**
     * Oldest moment whose `SENSOR_CHANGE` events belong to the session being corrected.
     *
     * Both the events the plugin wrote by itself and anything the user logged in between have to go,
     * or the newest of them keeps winning the dashboard's "last sensor change" lookup and the
     * corrected date stays invisible. Moving the date **earlier** has to reach back to the new date;
     * moving it **later** has to reach back to the old one.
     */
    fun cleanupFrom(newStartMs: Long, currentStartMs: Long): Long = minOf(newStartMs, currentStartMs)
}
