package app.aaps.plugins.source

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import kotlin.math.abs

/**
 * Pure mapping / gate helpers for Dexcom ONE+ → [GV] ingest.
 * Kept beside [DexcomOnePlusPlugin] so A8 UI edits stay out of the insert path.
 *
 * Dedup mirrors Ob1 `getForPreciseTimestamp(..., 4 min)` so backfill + live EGV
 * (and EGV re-poll) do not double-insert near-duplicate points.
 */
internal object DexcomOnePlusIngest {

    /** Ob1 precise-timestamp window. */
    const val DEDUP_WINDOW_MS: Long = 4L * 60L * 1000L

    private const val RECENT_CAP: Int = 64

    private val lock = Any()
    private val recentTimestampsMs = ArrayDeque<Long>()
    private val recentSequences = LinkedHashSet<Long>()

    /**
     * Block PersistenceLayer inserts only while the protocol/UI reports `WARMING`.
     * PAIRING / IDLE must not block — otherwise attach-to-ready sensors never ingest.
     */
    fun isWarmupBlockingIngest(phase: OnePlusWarmupState.Phase): Boolean =
        phase == OnePlusWarmupState.Phase.WARMING

    /**
     * @return true if this sample should be inserted (not a near-duplicate of a recent one).
     */
    fun shouldAccept(sample: OnePlusGlucoseSample): Boolean {
        synchronized(lock) {
            val seq = sample.sequence
            if (seq != null && recentSequences.contains(seq)) {
                return false
            }
            val ts = sample.timestampMs
            for (prev in recentTimestampsMs) {
                if (abs(prev - ts) < DEDUP_WINDOW_MS) {
                    return false
                }
            }
            recentTimestampsMs.addLast(ts)
            if (seq != null) {
                recentSequences.add(seq)
            }
            while (recentTimestampsMs.size > RECENT_CAP) {
                recentTimestampsMs.removeFirst()
            }
            while (recentSequences.size > RECENT_CAP) {
                val first = recentSequences.first()
                recentSequences.remove(first)
            }
            return true
        }
    }

    fun mapToGv(sample: OnePlusGlucoseSample): GV =
        GV(
            timestamp = sample.timestampMs,
            value = sample.mgdl,
            raw = null,
            noise = null,
            trendArrow = TrendArrow.fromString(sample.trendArrowRaw),
            sourceSensor = SourceSensor.DEXCOM_ONEPLUS_NATIVE,
        )

    /** Test-only: clear in-memory dedup state. */
    internal fun clearDedupForTests() {
        synchronized(lock) {
            recentTimestampsMs.clear()
            recentSequences.clear()
        }
    }
}
