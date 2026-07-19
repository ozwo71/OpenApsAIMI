package app.aaps.plugins.dexcomoneplus.reconnect

import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OemAwareReconnectPolicyTest {

    private val policy = OemAwareReconnectPolicy()

    @Test
    fun `pixel profile retries within connectRetryCount`() {
        val profile = DeviceProfileRegistry.PixelDefault
        assertThat(policy.shouldRetry(0, profile)).isTrue()
        assertThat(policy.shouldRetry(profile.connectRetryCount - 1, profile)).isTrue()
        assertThat(policy.shouldRetry(profile.connectRetryCount, profile)).isFalse()
        assertThat(policy.nextDelayMs(0, profile)).isEqualTo(profile.connectRetryDelayMs)
    }

    @Test
    fun `aggressive profile scales delay`() {
        val profile = DeviceProfileRegistry.SamsungDefault
        assertThat(profile.aggressiveReconnect).isTrue()
        assertThat(profile.connectRetryDelayMs).isEqualTo(10_000L)
        assertThat(profile.requestMtuOnConnect).isFalse()
        assertThat(profile.autoConnectFromAttempt).isEqualTo(2)
        assertThat(policy.nextDelayMs(0, profile)).isEqualTo(profile.connectRetryDelayMs)
        assertThat(policy.nextDelayMs(2, profile)).isEqualTo(profile.connectRetryDelayMs * 3)
    }
}
