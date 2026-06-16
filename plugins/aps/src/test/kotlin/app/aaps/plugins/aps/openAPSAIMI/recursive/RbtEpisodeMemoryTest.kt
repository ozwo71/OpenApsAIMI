package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RbtEpisodeMemoryTest {

    @BeforeEach
    fun reset() {
        RbtEpisodeMemory.clearForTests()
    }

    @Test
    fun post_hypo_episode_starts_and_expires() {
        val t0 = 1_000_000L
        val episode = RbtEpisodeMemory.tick(
            nowMs = t0,
            postHypoReboundProb = 0.75,
            chaosScore = 0.1,
            mealProb = 0.2,
        )
        assertThat(episode?.kind).isEqualTo(RbtEpisodeMemory.EpisodeKind.POST_HYPO_REBOUND)
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 30 * 60_000L)).isNotNull()
        assertThat(RbtEpisodeMemory.activeEpisode(t0 + 91 * 60_000L)).isNull()
    }

    @Test
    fun chaos_episode_overrides_post_hypo() {
        val t0 = 2_000_000L
        RbtEpisodeMemory.tick(t0, postHypoReboundProb = 0.65, chaosScore = 0.1, mealProb = 0.1)
        val chaotic = RbtEpisodeMemory.tick(
            t0 + 5 * 60_000L,
            postHypoReboundProb = 0.50,
            chaosScore = 0.80,
            mealProb = 0.1,
        )
        assertThat(chaotic?.kind).isEqualTo(RbtEpisodeMemory.EpisodeKind.CHAOTIC)
    }
}
