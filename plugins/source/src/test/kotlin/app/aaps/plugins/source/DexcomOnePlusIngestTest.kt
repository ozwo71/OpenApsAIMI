package app.aaps.plugins.source

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DexcomOnePlusIngestTest {

    @BeforeEach
    fun clearDedup() {
        DexcomOnePlusIngest.clearDedupForTests()
    }

    @Test
    fun `mapToGv uses native source sensor and trend`() {
        val sample = OnePlusGlucoseSample(
            mgdl = 118.0,
            timestampMs = 1_700_000_000_000L,
            trendArrowRaw = "FortyFiveUp",
            sequence = 42L,
        )

        val gv = DexcomOnePlusIngest.mapToGv(sample)

        assertThat(gv.timestamp).isEqualTo(1_700_000_000_000L)
        assertThat(gv.value).isEqualTo(118.0)
        assertThat(gv.raw).isNull()
        assertThat(gv.noise).isNull()
        assertThat(gv.trendArrow).isEqualTo(TrendArrow.FORTY_FIVE_UP)
        assertThat(gv.sourceSensor).isEqualTo(SourceSensor.DEXCOM_ONEPLUS_NATIVE)
    }

    @Test
    fun `mapToGv defaults unknown trend to NONE`() {
        val gv = DexcomOnePlusIngest.mapToGv(
            OnePlusGlucoseSample(mgdl = 90.0, timestampMs = 1L, trendArrowRaw = null)
        )
        assertThat(gv.trendArrow).isEqualTo(TrendArrow.NONE)
    }

    @Test
    fun `warmup WARMING blocks ingest`() {
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.WARMING)).isTrue()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.READY)).isFalse()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.IDLE)).isFalse()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.PAIRING)).isFalse()
        assertThat(DexcomOnePlusIngest.isWarmupBlockingIngest(OnePlusWarmupState.Phase.FAILED)).isFalse()
    }

    @Test
    fun `shouldAccept drops near-duplicate timestamps`() {
        val a = OnePlusGlucoseSample(mgdl = 100.0, timestampMs = 1_000_000L)
        val near = OnePlusGlucoseSample(mgdl = 101.0, timestampMs = 1_000_000L + 60_000L)
        val far = OnePlusGlucoseSample(mgdl = 102.0, timestampMs = 1_000_000L + DexcomOnePlusIngest.DEDUP_WINDOW_MS)
        assertThat(DexcomOnePlusIngest.shouldAccept(a)).isTrue()
        assertThat(DexcomOnePlusIngest.shouldAccept(near)).isFalse()
        assertThat(DexcomOnePlusIngest.shouldAccept(far)).isTrue()
    }

    @Test
    fun `shouldAccept drops duplicate sequence`() {
        val a = OnePlusGlucoseSample(mgdl = 110.0, timestampMs = 10L, sequence = 7L)
        val b = OnePlusGlucoseSample(mgdl = 111.0, timestampMs = 10L + DexcomOnePlusIngest.DEDUP_WINDOW_MS, sequence = 7L)
        assertThat(DexcomOnePlusIngest.shouldAccept(a)).isTrue()
        assertThat(DexcomOnePlusIngest.shouldAccept(b)).isFalse()
    }
}
