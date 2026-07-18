package app.aaps.plugins.dexcomoneplus

/**
 * Logcat greppable markers for ONE+ native session (product §6.3 / A6.11).
 * Prefer logging the constant as a substring so filters like `DEXCOM_ONEPLUS_SESSION` work.
 */
object OnePlusLogMarkers {
    const val TAG = "DEXCOM_ONEPLUS"

    const val SESSION = "DEXCOM_ONEPLUS_SESSION"
    const val WARMUP = "DEXCOM_ONEPLUS_WARMUP"
    const val BG = "DEXCOM_ONEPLUS_BG"
    const val ERROR = "DEXCOM_ONEPLUS_ERROR"
    const val SCAN = "DEXCOM_ONEPLUS_SCAN"
    const val RECONNECT = "DEXCOM_ONEPLUS_RECONNECT"
    const val STUB = "ONEPLUS_DRIVER_STUB"
}
