package app.aaps.plugins.dexcomoneplus.scan

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OnePlusBleScannerAndroidTest {

    @Test
    fun `nameMatches accepts DX02 DX01 DXCM family only`() {
        assertThat(OnePlusBleScannerAndroid.nameMatches("DXCM12")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("DX02aS")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("DX01xx")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("DexcomONE")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Dexcom65")).isFalse()
    }

    @Test
    fun `nameMatches rejects unrelated names`() {
        assertThat(OnePlusBleScannerAndroid.nameMatches(null)).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Eversense365")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Galaxy Watch")).isFalse()
    }

    @Test
    fun `target address is normalized for hardware scan filter`() {
        assertThat(
            OnePlusBleScannerAndroid.normalizeTargetAddress("da:3b:12:0d:5b:b7"),
        ).isEqualTo("DA:3B:12:0D:5B:B7")
    }
}
