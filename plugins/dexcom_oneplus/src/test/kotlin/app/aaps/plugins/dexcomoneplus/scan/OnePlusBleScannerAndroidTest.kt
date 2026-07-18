package app.aaps.plugins.dexcomoneplus.scan

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OnePlusBleScannerAndroidTest {

    @Test
    fun `nameMatches accepts DXC and Dex prefixes`() {
        assertThat(OnePlusBleScannerAndroid.nameMatches("DXCM12")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("dxc01")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("DexcomONE")).isTrue()
    }

    @Test
    fun `nameMatches rejects unrelated names`() {
        assertThat(OnePlusBleScannerAndroid.nameMatches(null)).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Eversense365")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Galaxy Watch")).isFalse()
    }
}
