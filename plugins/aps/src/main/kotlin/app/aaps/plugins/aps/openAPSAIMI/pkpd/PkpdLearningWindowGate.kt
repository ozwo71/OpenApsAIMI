package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Decides from the glucose curve itself if a tick is clean enough to learn DIA and peak from.
 *
 * Why it exists: the causal `learningQuality` score rewards a CONFIDENT meal guess, because its
 * ambiguity term is low when the meal belief is far from the protective belief. On two UAM days
 * (COB = 0 all day) 65 % and 77 % of the ticks that trained DIA/peak were inside a meal. With COB = 0
 * the estimator's own carb check (`carbsActiveG > 15`) cannot see the meal, so unannounced carbs
 * made insulin look slower and weaker than it is. The causal labels cannot fix this: on the same
 * days FAST_MEAL stayed dominant for 10 h in a row and every other tick was labelled DAWN or STRESS.
 *
 * So this gate does not read the labels. It accepts a tick only when glucose has not risen for a
 * full window and is not rising now — the only time the curve is mostly the work of insulin. It also
 * refuses while the meal belief is clearly up. That belief has a background plateau at about 0.40,
 * so the veto starts at [MEAL_VETO], never at 0.40.
 *
 * Few ticks pass (1–23 % of a day in the replays). That is the point: a few clean samples are better
 * than many biased ones, and the estimator's per-sample step is unchanged, so learning simply gets
 * slower. It never gets faster.
 *
 * Holds a short delta history. Feed it only from the one path that may learn ([record]), once per
 * tick. Not thread-safe by itself: the caller holds its own lock.
 */
internal class PkpdLearningWindowGate {

    data class Verdict(
        val pass: Boolean,
        /** Why the tick was refused, `null` when it passed. One of the `BLOCK_*` values. */
        val blockedBy: String?,
        /** Largest 5-min delta seen in the window before this tick, `null` with no history. */
        val maxRecentDeltaMgdl: Double?,
        /** Minutes of history the window really covers. */
        val coveredMin: Long,
    )

    private val history = ArrayDeque<Sample>()

    private data class Sample(val epochMin: Long, val deltaMgDlPer5: Double)

    /** Adds the delta of this tick. A second call for the same minute replaces the first one. */
    fun record(epochMin: Long, deltaMgDlPer5: Double) {
        if (!deltaMgDlPer5.isFinite()) return
        if (history.lastOrNull()?.epochMin == epochMin) history.removeLast()
        // A clock that went back makes the old history meaningless: start again.
        if (history.isNotEmpty() && epochMin < history.last().epochMin) history.clear()
        history.addLast(Sample(epochMin, deltaMgDlPer5))
        while (history.isNotEmpty() && epochMin - history.first().epochMin > HISTORY_KEEP_MIN) {
            history.removeFirst()
        }
    }

    /** Judges this tick against the history recorded BEFORE it. Does not change the history. */
    fun evaluate(epochMin: Long, deltaMgDlPer5: Double, mealConfidence: Double?): Verdict {
        val window = history.filter { it.epochMin < epochMin && epochMin - it.epochMin <= WINDOW_MIN }
        val coveredMin = window.firstOrNull()?.let { epochMin - it.epochMin } ?: 0L
        val maxRecent = window.maxOfOrNull { it.deltaMgDlPer5 }
        val blockedBy = when {
            !deltaMgDlPer5.isFinite()                    -> BLOCK_NO_DELTA
            (mealConfidence ?: 0.0) >= MEAL_VETO         -> BLOCK_MEAL_BELIEF
            deltaMgDlPer5 > MAX_DELTA_NOW_MGDL5          -> BLOCK_RISING_NOW
            window.size < MIN_SAMPLES || coveredMin < MIN_COVERED_MIN -> BLOCK_HISTORY_SHORT
            maxRecent != null && maxRecent > MAX_RECENT_RISE_MGDL5 -> BLOCK_RECENT_RISE
            else                                         -> null
        }
        return Verdict(
            pass = blockedBy == null,
            blockedBy = blockedBy,
            maxRecentDeltaMgdl = maxRecent,
            coveredMin = coveredMin,
        )
    }

    fun clear() = history.clear()

    companion object {

        /** Glucose must not have risen during this many minutes before the tick. */
        const val WINDOW_MIN = 60L

        /** The window must really be covered: a loop gap is not a quiet hour. */
        const val MIN_COVERED_MIN = 50L
        const val MIN_SAMPLES = 6

        /** History older than this is dropped. A bit more than [WINDOW_MIN] so the edge is kept. */
        private const val HISTORY_KEEP_MIN = WINDOW_MIN + 10L

        /** Largest 5-min delta allowed in the window (mg/dL per 5 min). Above it a carb source was active. */
        const val MAX_RECENT_RISE_MGDL5 = 2.0

        /** Largest 5-min delta allowed on the tick itself. */
        const val MAX_DELTA_NOW_MGDL5 = 1.0

        /** Meal belief at or above this blocks. Above the ~0.40 background plateau on purpose. */
        const val MEAL_VETO = 0.50

        /**
         * Causal learning quality needed when this gate is armed. Lower than
         * `CausalStatePosterior.LEARNING_QUALITY_MIN` because the curve test above now does the
         * work that score could not do; the score only still removes a really poor sensor.
         */
        const val QUALITY_MIN_WHEN_ARMED = 0.30

        const val BLOCK_NO_DELTA = "no_delta"
        const val BLOCK_MEAL_BELIEF = "meal_belief"
        const val BLOCK_RISING_NOW = "rising_now"
        const val BLOCK_HISTORY_SHORT = "history_short"
        const val BLOCK_RECENT_RISE = "recent_rise"
    }
}
