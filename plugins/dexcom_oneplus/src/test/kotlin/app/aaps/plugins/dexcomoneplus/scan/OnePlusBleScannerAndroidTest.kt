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

    @Test
    fun `FEBC neighbours count as G7 family unless they carry a G6 marketing name`() {
        // G7 / ONE+ frequently advertise without a local name.
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement(null)).isTrue()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("DX021H")).isTrue()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("DXCM99")).isTrue()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("Dexcom65")).isFalse()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("Galaxy Watch")).isFalse()
    }

    @Test
    fun `wait result exposes the strongest foreign transmitter for the stale-MAC diagnostic`() {
        val weak = OnePlusScanResult(address = "AA:BB:CC:DD:EE:01", name = "DX021H", rssi = -88)
        val strong = OnePlusScanResult(address = "AA:BB:CC:DD:B8:92", name = "DX021H", rssi = -51)

        val result = OnePlusAdvWaitResult(target = null, foreign = listOf(weak, strong))

        assertThat(result.strongestForeign()).isEqualTo(strong)
        assertThat(OnePlusAdvWaitResult().strongestForeign()).isNull()
    }
}
