package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.HR
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UnifiedActivityProviderMTRTest {

    private val now = System.currentTimeMillis()

    @Test
    fun `auto mode prefers latest Garmin HR over Health Connect`() {
        val result = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(
                hr("HealthConnect", 72.0, now - 60_000),
                hr("Garmin-Watchface", 88.0, now - 30_000),
            ),
            UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.bpm).isWithin(0.001).of(88.0)
        assertThat(result.source).isEqualTo("Garmin-Watchface")
    }

    @Test
    fun `auto mode uses legacy Garmin device tag`() {
        val result = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(hr("Garmin", 95.0, now - 10_000)),
            UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.bpm).isWithin(0.001).of(95.0)
        assertThat(result.source).isEqualTo("Garmin")
    }

    @Test
    fun `prefer wear uses Wear then Garmin fallback`() {
        val wearFirst = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(
                hr("Garmin-Watchface", 90.0, now - 5_000),
                hr("Pixel Watch", 78.0, now - 2_000),
            ),
            UnifiedActivityProviderMTR.MODE_PREFER_WEAR,
        )
        assertThat(wearFirst).isNotNull()
        assertThat(wearFirst!!.source).isEqualTo("Pixel Watch")
        assertThat(wearFirst.bpm).isWithin(0.001).of(78.0)

        val garminOnly = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(hr("Garmin-Watchface", 90.0, now - 2_000)),
            UnifiedActivityProviderMTR.MODE_PREFER_WEAR,
        )
        assertThat(garminOnly).isNotNull()
        assertThat(garminOnly!!.source).isEqualTo("Garmin-Watchface")
    }

    @Test
    fun `hc only ignores Garmin HR`() {
        val result = UnifiedActivityProviderMTR.resolveLatestHeartRate(
            listOf(
                hr("Garmin-Watchface", 90.0, now - 5_000),
                hr("HealthConnect", 70.0, now - 60_000),
            ),
            UnifiedActivityProviderMTR.MODE_HEALTH_CONNECT_ONLY,
        )

        assertThat(result).isNotNull()
        assertThat(result!!.source).isEqualTo("HealthConnect")
        assertThat(result.bpm).isWithin(0.001).of(70.0)
    }

    @Test
    fun `disabled mode returns null`() {
        assertThat(
            UnifiedActivityProviderMTR.resolveLatestHeartRate(
                listOf(hr("Garmin-Watchface", 90.0, now)),
                UnifiedActivityProviderMTR.MODE_DISABLED,
            )
        ).isNull()
    }

    @Test
    fun `legacy Garmin tag is not classified as Wear`() {
        assertThat(UnifiedActivityProviderMTR.isWearDevice("Garmin")).isFalse()
        assertThat(UnifiedActivityProviderMTR.isGarminDevice("Garmin")).isTrue()
    }

    private fun hr(device: String, bpm: Double, timestamp: Long): HR =
        HR(
            duration = 300_000L,
            timestamp = timestamp,
            beatsPerMinute = bpm,
            device = device,
        )
}
