package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TapSitePeakShiftTest {

    @Test
    fun `fresh site has no shift`() {
        assertEquals(0.0, TapSitePeakShift.minutesForSiteAge(0f), 0.001)
        assertEquals(0.0, TapSitePeakShift.minutesForSiteAge(1.9f), 0.001)
    }

    @Test
    fun `aged site ramps then caps`() {
        assertEquals(0.0, TapSitePeakShift.minutesForSiteAge(2f), 0.001)
        assertEquals(0.45, TapSitePeakShift.minutesForSiteAge(3f), 0.001)
        assertEquals(5.0, TapSitePeakShift.minutesForSiteAge(30f), 0.001)
    }
}
