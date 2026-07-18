package app.aaps.plugins.dexcomoneplus.oem

/**
 * BLE/runtime knobs for a phone OEM family (Q9 v1).
 * Stubs with sensible defaults — A6 applies these; do not hardcode GATT here.
 */
data class OemDeviceProfile(
    val id: OemProfileId,
    /** GATT connect attempt timeout. */
    val connectTimeoutMs: Long,
    /** Max connect attempts before surfacing failure. */
    val connectRetryCount: Int,
    /** Delay between connect retries. */
    val connectRetryDelayMs: Long,
    /** Preferred ATT MTU request (OS may negotiate lower). */
    val preferredMtu: Int,
    /** Keep BLE session behind a foreground service when true. */
    val useForegroundService: Boolean,
    /** Faster / denser reconnect backoff when true. */
    val aggressiveReconnect: Boolean,
)

enum class OemProfileId {
    PIXEL,
    SAMSUNG,
    GENERIC_FALLBACK,
}
