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
    /**
     * Sleep after GATT [android.bluetooth.BluetoothGatt.close] before next connectGatt.
     * Ob1/Samsung: avoids status 133/147 races on immediate reconnect.
     */
    val postCloseSettleMs: Long = 2_000L,
    /** Pause after stopScan / ADV sighting before connectGatt (Ob1 CONNECT_NOW ≈ 500 ms). */
    val scanHandoffMs: Long = 500L,
    /**
     * On reconnect attempts, LE-scan until the target MAC advertises (or timeout).
     * 0 = skip (MAC-only connectGatt).
     */
    val preConnectScanMs: Long = 0L,
    /**
     * When true, request MTU on CONNECTED before discover.
     * When false, discover (+ CCCD) first — Ob1 never requests MTU; safer on Samsung.
     */
    val requestMtuOnConnect: Boolean = true,
    /** Call hidden BluetoothGatt.refresh() before close / reconnect. */
    val useGattRefresh: Boolean = false,
    /**
     * Use connectGatt(autoConnect=true) when attempt index >= this.
     * -1 = never (hard connect only).
     */
    val autoConnectFromAttempt: Int = -1,
    /** Optional pause after services discovered before CCCD (Ob1 Samsung pairing workaround). */
    val postDiscoverDelayMs: Long = 0L,
)

enum class OemProfileId {
    PIXEL,
    SAMSUNG,
    GENERIC_FALLBACK,
}
