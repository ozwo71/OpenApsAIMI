package app.aaps.plugins.dexcomoneplus.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusBluetoothUuids
import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Platform LE scanner for Dexcom ONE+ / G7-family ADV.
 *
 * Soft filters (xDrip Ob1 + Juggluco `isG7`):
 * - Local name `DXCM` / `DX02` / `DX01` / `DXC*` / `Dex*`, and/or
 * - Advertisement includes service UUID FEBC ([OnePlusBluetoothUuids.Advertisement]).
 * - Optional [sessionHint] sticky-matches last MAC / ADV name / serial.
 *
 * Unfiltered LE scan (Eversense-style) so transmitters without name still appear if UUID matches.
 *
 * ⚠️ ASYNC IMPACT: [OnePlusScanListener.onDevice] on binder thread — hop to main for Compose.
 */
@SuppressLint("MissingPermission")
class OnePlusBleScannerAndroid(
    context: Context,
    /** Optional stored session for Juggluco-style candidate ranking / sticky match. */
    @Volatile var sessionHint: OnePlusStoredSession? = null,
) : OnePlusBleScanner {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val scanning = AtomicBoolean(false)
    private val seen = ConcurrentHashMap<String, OnePlusScanResult>()

    @Volatile
    private var listener: OnePlusScanListener? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: failed errorCode=$errorCode")
            scanning.set(false)
        }
    }

    override fun startScan(listener: OnePlusScanListener) {
        val adapter = bluetoothManager.adapter
        val leScanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || leScanner == null) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: adapter unavailable")
            return
        }
        stopScan()
        seen.clear()
        this.listener = listener
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        leScanner.startScan(null, settings, callback)
        scanning.set(true)
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: started")
    }

    override fun stopScan() {
        val leScanner = bluetoothManager.adapter?.bluetoothLeScanner
        try {
            leScanner?.stopScan(callback)
        } catch (_: Throwable) {
        }
        scanning.set(false)
        listener = null
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: stopped")
    }

    override fun isScanning(): Boolean = scanning.get()

    private fun handle(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = device.name ?: result.scanRecord?.deviceName
        val hasAdvUuid = result.scanRecord?.serviceUuids?.any {
            it.uuid == OnePlusBluetoothUuids.Advertisement
        } == true
        val hint = sessionHint
        val candidate = OnePlusAdvCandidate.isCandidate(name, address, hint)
        if (!candidate && !hasAdvUuid && !nameMatches(name)) return
        // When we have a sticky session, drop weak unrelated FEBC noise unless name/MAC matches.
        if (hint != null && !candidate && hasAdvUuid && !nameMatches(name)) return

        val hit = OnePlusScanResult(address = address, name = name, rssi = result.rssi)
        val previous = seen.put(address, hit)
        if (previous == null || previous.rssi != hit.rssi || previous.name != hit.name) {
            val score = OnePlusAdvCandidate.rankScore(name, address, hit.rssi, hint)
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: device name=${name ?: "?"} rssi=${hit.rssi} " +
                    "score=$score addr=***${address.takeLast(5)}",
            )
            listener?.onDevice(hit)
        }
    }

    companion object {
        fun nameMatches(name: String?): Boolean = OnePlusAdvCandidate.nameMatchesSoft(name)
    }
}
