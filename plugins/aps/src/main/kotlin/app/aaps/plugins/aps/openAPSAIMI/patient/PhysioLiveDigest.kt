package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import org.json.JSONObject

/**
 * Live wearable / phone physio signals shown in Context UI between full loop recomputes.
 */
data class PhysioLiveDigest(
    val stepsLast15m: Int = 0,
    val stepsLast60m: Int = 0,
    val hrNowBpm: Int = 0,
    val hrAvg15mBpm: Int = 0,
    val rhrRestingBpm: Int = 0,
    val activityState: String = "IDLE",
    val sleepDebtMinutes: Int = 0,
    val snapshotAgeMs: Long = 0L,
    val source: String = "Unknown",
    val confidence: Double = 0.0,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("steps_last_15m", stepsLast15m)
            put("steps_last_60m", stepsLast60m)
            put("hr_now_bpm", hrNowBpm)
            put("hr_avg_15m_bpm", hrAvg15mBpm)
            put("rhr_resting_bpm", rhrRestingBpm)
            put("activity_state", activityState)
            put("sleep_debt_minutes", sleepDebtMinutes)
            put("snapshot_age_ms", snapshotAgeMs)
            put("source", source)
            put("confidence", confidence)
        }

    companion object {
        fun from(snapshot: HealthContextSnapshot, nowMs: Long): PhysioLiveDigest {
            val ageMs = if (snapshot.timestamp > 0L) {
                (nowMs - snapshot.timestamp).coerceAtLeast(0L)
            } else {
                0L
            }
            return PhysioLiveDigest(
                stepsLast15m = snapshot.stepsLast15m,
                stepsLast60m = snapshot.stepsLast60m,
                hrNowBpm = snapshot.hrNow,
                hrAvg15mBpm = snapshot.hrAvg15m,
                rhrRestingBpm = snapshot.rhrResting,
                activityState = snapshot.activityState,
                sleepDebtMinutes = snapshot.sleepDebtMinutes,
                snapshotAgeMs = ageMs,
                source = snapshot.source,
                confidence = snapshot.confidence,
            )
        }
    }
}
