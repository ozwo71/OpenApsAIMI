package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.max

/**
 * Macro-scale episodic memory for post-hypo rebound and chaotic intervals (τ180 semantics).
 * Complements per-scale [RecursiveBeliefMemory] belief-echo rings with clinically named episodes.
 */
object RbtEpisodeMemory {

    private const val POST_HYPO_START_THRESHOLD = 0.60
    private const val POST_HYPO_TTL_MS = 90L * 60L * 1000L
    private const val CHAOS_START_THRESHOLD = 0.72
    private const val CHAOS_TTL_MS = 45L * 60L * 1000L
    private const val MEAL_EXTEND_POST_HYPO_MEAL_PROB = 0.55

    enum class EpisodeKind {
        POST_HYPO_REBOUND,
        CHAOTIC,
    }

    data class EpisodeState(
        val kind: EpisodeKind,
        val startedAtMs: Long,
        val lastSeenAtMs: Long,
        val peakScore: Double,
        val tickCount: Int,
    ) {
        fun ageMinutes(nowMs: Long): Double =
            ((nowMs - startedAtMs).coerceAtLeast(0L) / 60_000.0)

        fun isExpired(nowMs: Long): Boolean {
            val ttl = when (kind) {
                EpisodeKind.POST_HYPO_REBOUND -> POST_HYPO_TTL_MS
                EpisodeKind.CHAOTIC -> CHAOS_TTL_MS
            }
            return nowMs - lastSeenAtMs > ttl
        }
    }

    @Volatile
    private var active: EpisodeState? = null

    fun tick(
        nowMs: Long,
        postHypoReboundProb: Double,
        chaosScore: Double,
        mealProb: Double,
    ): EpisodeState? {
        val current = active?.takeUnless { it.isExpired(nowMs) }
        val postHypoSignal = postHypoReboundProb.coerceIn(0.0, 1.0)
        val chaosSignal = chaosScore.coerceIn(0.0, 1.0)

        val candidateKind = when {
            chaosSignal >= CHAOS_START_THRESHOLD -> EpisodeKind.CHAOTIC
            postHypoSignal >= POST_HYPO_START_THRESHOLD -> EpisodeKind.POST_HYPO_REBOUND
            else -> null
        }

        active = when {
            candidateKind == null && current == null -> null
            candidateKind == null -> current?.copy(lastSeenAtMs = nowMs)
            current == null -> EpisodeState(
                kind = candidateKind,
                startedAtMs = nowMs,
                lastSeenAtMs = nowMs,
                peakScore = max(postHypoSignal, chaosSignal),
                tickCount = 1,
            )
            current.kind == candidateKind || candidateKind == EpisodeKind.CHAOTIC -> {
                current.copy(
                    kind = if (candidateKind == EpisodeKind.CHAOTIC) EpisodeKind.CHAOTIC else current.kind,
                    lastSeenAtMs = nowMs,
                    peakScore = max(current.peakScore, max(postHypoSignal, chaosSignal)),
                    tickCount = current.tickCount + 1,
                )
            }
            current.kind == EpisodeKind.POST_HYPO_REBOUND &&
                candidateKind == EpisodeKind.POST_HYPO_REBOUND &&
                mealProb >= MEAL_EXTEND_POST_HYPO_MEAL_PROB -> {
                current.copy(
                    lastSeenAtMs = nowMs,
                    peakScore = max(current.peakScore, postHypoSignal),
                    tickCount = current.tickCount + 1,
                )
            }
            else -> EpisodeState(
                kind = candidateKind,
                startedAtMs = nowMs,
                lastSeenAtMs = nowMs,
                peakScore = max(postHypoSignal, chaosSignal),
                tickCount = 1,
            )
        }
        active = active?.takeUnless { it.isExpired(nowMs) }
        return active
    }

    fun activeEpisode(nowMs: Long): EpisodeState? =
        active?.takeUnless { it.isExpired(nowMs) }

    fun clearForTests() {
        active = null
    }
}
