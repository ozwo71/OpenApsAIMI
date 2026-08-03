package app.aaps.plugins.dexcomoneplus

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OnePlusCgmDriversTest {

    @AfterEach
    fun resetToStub() {
        OnePlusCgmDrivers.select(useReal = false)
    }

    @Test
    fun select_switchesStubAndReal() {
        val stub = OnePlusCgmDrivers.select(useReal = false)
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isFalse()
        assertThat(stub).isSameInstanceAs(OnePlusCgmDriverStub.instance)

        val real = OnePlusCgmDrivers.select(useReal = true)
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isTrue()
        assertThat(real).isSameInstanceAs(OnePlusCgmDrivers.realSkeleton())

        val back = OnePlusCgmDrivers.select(useReal = false)
        assertThat(OnePlusCgmDrivers.useRealSkeleton).isFalse()
        assertThat(back).isSameInstanceAs(OnePlusCgmDriverStub.instance)
    }
}
