package app.aaps.plugins.dexcomoneplus.identity

import android.content.Context
import android.util.Base64
import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers

/**
 * Persist ONE+ identity + last good MAC + KEKS shared key for Juggluco-style reconnect.
 *
 * Uses a private SharedPreferences file (not exportable AAPS prefs) — PIN / key stay local.
 */
class OnePlusSensorStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): OnePlusStoredSession? {
        val pin = prefs.getString(KEY_PIN, null)?.takeIf { it.length == 4 } ?: return null
        val serial = prefs.getString(KEY_SERIAL, null)?.takeIf { it.isNotBlank() }
        val gtin = prefs.getString(KEY_GTIN, null)?.takeIf { it.isNotBlank() }
        val raw = prefs.getString(KEY_RAW_GS1, null)?.takeIf { it.isNotBlank() }
        val mac = prefs.getString(KEY_MAC, null)?.takeIf { it.isNotBlank() }
        val deviceName = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
        val keyB64 = prefs.getString(KEY_SHARED, null)
        val shared = keyB64?.let {
            try {
                Base64.decode(it, Base64.NO_WRAP)
            } catch (_: Throwable) {
                null
            }
        }?.takeIf { it.size == 16 }
        return OnePlusStoredSession(
            identity = OnePlusSensorIdentity(pin = pin, serial = serial, gtin = gtin, rawGs1 = raw),
            lastMac = mac,
            lastDeviceName = deviceName,
            sharedKey = shared,
        )
    }

    fun saveIdentity(identity: OnePlusSensorIdentity) {
        prefs.edit()
            .putString(KEY_PIN, identity.pin)
            .putString(KEY_SERIAL, identity.serial)
            .putString(KEY_GTIN, identity.gtin)
            .putString(KEY_RAW_GS1, identity.rawGs1)
            .apply()
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: sensor identity saved serial=${identity.serial ?: "-"}",
        )
    }

    fun saveLastMac(address: String) {
        if (address.isBlank()) return
        prefs.edit().putString(KEY_MAC, address.uppercase()).apply()
    }

    fun saveLastDeviceName(name: String?) {
        if (name.isNullOrBlank()) return
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    fun saveSharedKey(key: ByteArray) {
        if (key.size != 16) return
        prefs.edit()
            .putString(KEY_SHARED, Base64.encodeToString(key, Base64.NO_WRAP))
            .apply()
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: KEKS shared key persisted (16b)",
        )
    }

    fun clearSharedKey() {
        prefs.edit().remove(KEY_SHARED).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "dexcom_oneplus_sensor"
        private const val KEY_PIN = "pin"
        private const val KEY_SERIAL = "serial"
        private const val KEY_GTIN = "gtin"
        private const val KEY_RAW_GS1 = "raw_gs1"
        private const val KEY_MAC = "last_mac"
        private const val KEY_DEVICE_NAME = "last_device_name"
        private const val KEY_SHARED = "shared_key_b64"
    }
}
