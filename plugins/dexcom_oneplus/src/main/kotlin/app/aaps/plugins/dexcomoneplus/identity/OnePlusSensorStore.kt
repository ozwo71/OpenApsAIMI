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
class OnePlusSensorStore(context: Context, namespace: String? = null) {

    // Per-slot isolation: the STAGING sensor must not share identity / MAC / KEKS key / ingest
    // markers with PRODUCTION. namespace == null → the original single-sensor file (non-breaking).
    private val prefsName = if (namespace.isNullOrBlank()) PREFS_NAME else "${PREFS_NAME}_$namespace"
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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
        // A new sensor restarts its EGV sequence counter from a low value, so the persisted ingest
        // high-water mark (last sequence/timestamp) MUST be reset — otherwise the new sensor's early
        // readings would be wrongly rejected as "already seen". Reset only on an actual serial change.
        val serialChanged = identity.serial != null && identity.serial != prefs.getString(KEY_SERIAL, null)
        prefs.edit()
            .putString(KEY_PIN, identity.pin)
            .putString(KEY_SERIAL, identity.serial)
            .putString(KEY_GTIN, identity.gtin)
            .putString(KEY_RAW_GS1, identity.rawGs1)
            .apply()
        if (serialChanged) {
            clearLastIngest()
            prefs.edit().remove(KEY_SESSION_START).apply()
        }
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: sensor identity saved serial=${identity.serial ?: "-"} serialChanged=$serialChanged",
        )
    }

    /**
     * Persist the ingest high-water mark so a process restart / app update cannot re-insert already
     * stored readings (which the loop rejects as duplicates). [sequence] is the monotonic per-sensor
     * EGV counter; [timestampMs] backs the near-duplicate time window.
     */
    fun saveLastIngest(sequence: Long?, timestampMs: Long) {
        prefs.edit().apply {
            if (sequence != null) putLong(KEY_LAST_SEQ, sequence)
            putLong(KEY_LAST_TS, timestampMs)
        }.apply()
    }

    /** Last ingested EGV sequence, or -1 when none recorded (accept everything). */
    fun loadLastIngestSequence(): Long = prefs.getLong(KEY_LAST_SEQ, -1L)

    /** Last ingested reading timestamp (ms), or 0 when none recorded. */
    fun loadLastIngestTimestamp(): Long = prefs.getLong(KEY_LAST_TS, 0L)

    private fun clearLastIngest() {
        prefs.edit().remove(KEY_LAST_SEQ).remove(KEY_LAST_TS).apply()
    }

    /** Record the sensor session start (epoch ms) once — used to derive early/end-of-life age. */
    fun saveSessionStartIfAbsent(epochMs: Long) {
        if (!prefs.contains(KEY_SESSION_START)) prefs.edit().putLong(KEY_SESSION_START, epochMs).apply()
    }

    /** Sensor session start (epoch ms), or 0 when unknown. */
    fun loadSessionStart(): Long = prefs.getLong(KEY_SESSION_START, 0L)

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

    /**
     * Adopt another sensor's stored session into this store — used on staging→production promotion so
     * the production driver durably resumes the promoted sensor after a restart. Copies identity / MAC
     * / device name / KEKS key + its [sessionStartMs], and clears the ingest high-water mark (the
     * promoted sensor has a fresh EGV sequence space, so no old floor must survive).
     */
    fun adopt(session: OnePlusStoredSession, sessionStartMs: Long) {
        val edit = prefs.edit()
            .putString(KEY_PIN, session.identity.pin)
            .putString(KEY_SERIAL, session.identity.serial)
            .putString(KEY_GTIN, session.identity.gtin)
            .putString(KEY_RAW_GS1, session.identity.rawGs1)
            .remove(KEY_LAST_SEQ)
            .remove(KEY_LAST_TS)
        session.lastMac?.let { edit.putString(KEY_MAC, it) }
        session.lastDeviceName?.let { edit.putString(KEY_DEVICE_NAME, it) }
        session.sharedKey?.let { edit.putString(KEY_SHARED, Base64.encodeToString(it, Base64.NO_WRAP)) }
        if (sessionStartMs > 0L) edit.putLong(KEY_SESSION_START, sessionStartMs)
        edit.apply()
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: adopted promoted sensor serial=${session.identity.serial ?: "-"}")
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
        private const val KEY_LAST_SEQ = "last_ingest_seq"
        private const val KEY_LAST_TS = "last_ingest_ts"
        private const val KEY_SESSION_START = "session_start_ms"
    }
}
