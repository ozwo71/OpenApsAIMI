package app.aaps.plugins.source.compose

import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DexcomOnePlusWarmupCountdownTest {

    private fun warming(remainingMs: Long? = null, endsAtEpochMs: Long? = null) =
        OnePlusWarmupState(
            phase = OnePlusWarmupState.Phase.WARMING,
            remainingMs = remainingMs,
            endsAtEpochMs = endsAtEpochMs,
        )

    // ---- formatMmSs ----

    @Test
    fun `formatMmSs formats minutes and zero-padded seconds`() {
        assertThat(DexcomOnePlusWarmupCountdown.formatMmSs(28L * 60_000L + 14_000L)).isEqualTo("28:14")
        assertThat(DexcomOnePlusWarmupCountdown.formatMmSs(5_000L)).isEqualTo("0:05")
        assertThat(DexcomOnePlusWarmupCountdown.formatMmSs(60_000L)).isEqualTo("1:00")
    }

    @Test
    fun `formatMmSs clamps negative to zero`() {
        assertThat(DexcomOnePlusWarmupCountdown.formatMmSs(-5_000L)).isEqualTo("0:00")
    }

    // ---- resolveRemainingMs (priority: remainingMs > endsAt > localFallback) ----

    @Test
    fun `resolveRemainingMs prefers protocol remainingMs over everything`() {
        val out = DexcomOnePlusWarmupCountdown.resolveRemainingMs(
            state = warming(remainingMs = 90_000L, endsAtEpochMs = 1_000L),
            nowEpochMs = 10_000L,
            localFallbackEndsAtEpochMs = 999_999L,
        )
        assertThat(out).isEqualTo(90_000L)
    }

    @Test
    fun `resolveRemainingMs falls back to endsAt minus now when remaining is null`() {
        val out = DexcomOnePlusWarmupCountdown.resolveRemainingMs(
            state = warming(endsAtEpochMs = 100_000L),
            nowEpochMs = 40_000L,
            localFallbackEndsAtEpochMs = null,
        )
        assertThat(out).isEqualTo(60_000L)
    }

    @Test
    fun `resolveRemainingMs uses local fallback only when protocol values are null`() {
        val out = DexcomOnePlusWarmupCountdown.resolveRemainingMs(
            state = warming(),
            nowEpochMs = 40_000L,
            localFallbackEndsAtEpochMs = 100_000L,
        )
        assertThat(out).isEqualTo(60_000L)
    }

    @Test
    fun `resolveRemainingMs returns null when nothing is available`() {
        val out = DexcomOnePlusWarmupCountdown.resolveRemainingMs(
            state = warming(),
            nowEpochMs = 40_000L,
            localFallbackEndsAtEpochMs = null,
        )
        assertThat(out).isNull()
    }

    @Test
    fun `resolveRemainingMs coerces an elapsed endsAt to zero`() {
        val out = DexcomOnePlusWarmupCountdown.resolveRemainingMs(
            state = warming(endsAtEpochMs = 10_000L),
            nowEpochMs = 40_000L,
            localFallbackEndsAtEpochMs = null,
        )
        assertThat(out).isEqualTo(0L)
    }

    // ---- shouldStartLocalFallback ----

    @Test
    fun `shouldStartLocalFallback only when WARMING and both protocol values null`() {
        assertThat(DexcomOnePlusWarmupCountdown.shouldStartLocalFallback(warming())).isTrue()
        assertThat(DexcomOnePlusWarmupCountdown.shouldStartLocalFallback(warming(remainingMs = 1L))).isFalse()
        assertThat(DexcomOnePlusWarmupCountdown.shouldStartLocalFallback(warming(endsAtEpochMs = 1L))).isFalse()
        assertThat(
            DexcomOnePlusWarmupCountdown.shouldStartLocalFallback(
                OnePlusWarmupState(phase = OnePlusWarmupState.Phase.CONNECTING),
            ),
        ).isFalse()
    }
}
