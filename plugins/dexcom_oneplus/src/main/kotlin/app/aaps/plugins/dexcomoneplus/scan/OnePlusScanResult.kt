package app.aaps.plugins.dexcomoneplus.scan

/**
 * LE advertisement hit for ONE+ / G7-family transmitters.
 */
data class OnePlusScanResult(
    val address: String,
    val name: String?,
    val rssi: Int,
)
