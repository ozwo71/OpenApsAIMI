package app.aaps.plugins.source

import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DexcomOnePlusWarmupMapperTest {

    @Test
    fun `WARMING maps to active WARMING with fields passed through`() {
        val out = DexcomOnePlusWarmupMapper.toCgmWarmupStatus(
            OnePlusWarmupState(
                phase = OnePlusWarmupState.Phase.WARMING,
                remainingMs = 1_684_000L,
                endsAtEpochMs = 42L,
                message = "warming",
            ),
        )
        assertThat(out).isNotNull()
        assertThat(out!!.active).isTrue()
        assertThat(out.phase).isEqualTo(CgmWarmupStatus.Phase.WARMING)
        assertThat(out.remainingMs).isEqualTo(1_684_000L)
        assertThat(out.endsAtEpochMs).isEqualTo(42L)
        assertThat(out.message).isEqualTo("warming")
    }

    @Test
    fun `CONNECTING RECONNECTING PAIRING map to active with same phase`() {
        val cases = mapOf(
            OnePlusWarmupState.Phase.CONNECTING to CgmWarmupStatus.Phase.CONNECTING,
            OnePlusWarmupState.Phase.RECONNECTING to CgmWarmupStatus.Phase.RECONNECTING,
            OnePlusWarmupState.Phase.PAIRING to CgmWarmupStatus.Phase.PAIRING,
        )
        cases.forEach { (inPhase, outPhase) ->
            val out = DexcomOnePlusWarmupMapper.toCgmWarmupStatus(OnePlusWarmupState(phase = inPhase))
            assertThat(out).isNotNull()
            assertThat(out!!.active).isTrue()
            assertThat(out.phase).isEqualTo(outPhase)
        }
    }

    @Test
    fun `terminal and idle phases map to null`() {
        listOf(
            OnePlusWarmupState.Phase.READY,
            OnePlusWarmupState.Phase.IDLE,
            OnePlusWarmupState.Phase.FAILED,
        ).forEach { phase ->
            assertThat(DexcomOnePlusWarmupMapper.toCgmWarmupStatus(OnePlusWarmupState(phase = phase)))
                .isNull()
        }
    }

    @Test
    fun `null countdown fields are preserved on active status`() {
        val out = DexcomOnePlusWarmupMapper.toCgmWarmupStatus(
            OnePlusWarmupState(phase = OnePlusWarmupState.Phase.WARMING),
        )
        assertThat(out).isNotNull()
        assertThat(out!!.remainingMs).isNull()
        assertThat(out.endsAtEpochMs).isNull()
        assertThat(out.message).isNull()
    }
}
