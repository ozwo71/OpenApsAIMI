package app.aaps.plugins.dexcomoneplus.reconnect

import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile

/**
 * Reconnect / backoff policy stubs (A6.9). Uses A9 [OemDeviceProfile] knobs only — no GATT.
 */
interface OnePlusReconnectPolicy {
    fun shouldRetry(attempt: Int, profile: OemDeviceProfile): Boolean
    fun nextDelayMs(attempt: Int, profile: OemDeviceProfile): Long
}

class OemAwareReconnectPolicy : OnePlusReconnectPolicy {

    override fun shouldRetry(attempt: Int, profile: OemDeviceProfile): Boolean {
        if (attempt < 0) return false
        return attempt < profile.connectRetryCount
    }

    override fun nextDelayMs(attempt: Int, profile: OemDeviceProfile): Long {
        val base = profile.connectRetryDelayMs.coerceAtLeast(0L)
        if (!profile.aggressiveReconnect) return base
        // Mild linear backoff when OEM profile asks for denser reconnects.
        val factor = (attempt + 1).coerceAtLeast(1)
        return base * factor
    }
}
