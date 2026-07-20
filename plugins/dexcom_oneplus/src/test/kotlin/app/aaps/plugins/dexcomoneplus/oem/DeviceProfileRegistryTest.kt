package app.aaps.plugins.dexcomoneplus.oem

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DeviceProfileRegistryTest {

    @Test
    fun pixel_doesNotRequestMtuBeforeDiscover() {
        // Juggluco Dex path never requestMtu before discover; Pixel previously did and
        // could race CCCD/auth on fragile stacks.
        assertThat(DeviceProfileRegistry.PixelDefault.requestMtuOnConnect).isFalse()
        assertThat(DeviceProfileRegistry.SamsungDefault.requestMtuOnConnect).isFalse()
        assertThat(DeviceProfileRegistry.GenericFallback.requestMtuOnConnect).isFalse()
    }
}
