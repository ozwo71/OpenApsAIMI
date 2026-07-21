package app.aaps.plugins.dexcomoneplus.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusBluetoothUuids
import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Platform LE scanner for Dexcom ONE+ / G7-family ADV.
 *
 * Soft filters (Juggluco `isG7`):
 * - Local name `DXCM` / `DX02` / `DX01`, and/or
 * - Advertisement includes service UUID FEBC ([OnePlusBluetoothUuids.Advertisement])
 *   **without** a G6-style `Dexcom*` marketing name.
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

    /**
     * Pre-connect ADV wait isolated from UI [stopScan] (own [ScanCallback]).
     * Field log: StartActivity onDispose stopped shared scan ~0.6s into prepareConnect.
     */
    override fun awaitTargetMac(address: String, timeoutMs: Long): OnePlusScanResult? {
        val adapter = bluetoothManager.adapter
        val leScanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || leScanner == null) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: awaitTargetMac adapter unavailable")
            return null
        }
        val target = address.uppercase()
        val found = AtomicReference<OnePlusScanResult?>(null)
        val latch = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val addr = device.address ?: return
                if (addr.uppercase() != target) return
                val name = device.name ?: result.scanRecord?.deviceName
                val hit = OnePlusScanResult(addr, name, result.rssi)
                    .apply { seenElapsedMs = SystemClock.elapsedRealtime() }
                if (found.compareAndSet(null, hit)) {
                    latch.countDown()
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: awaitTargetMac failed errorCode=$errorCode",
                )
                latch.countDown()
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            leScanner.startScan(null, settings, cb)
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: awaitTargetMac started mac=***${target.takeLast(5)} " +
                    "timeoutMs=$timeoutMs",
            )
            latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            found.get()
        } catch (t: Throwable) {
            Log.w(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: awaitTargetMac ${t.message}")
            null
        } finally {
            try {
                leScanner.stopScan(cb)
            } catch (_: Throwable) {
            }
            Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: awaitTargetMac stopped")
        }
    }

    private fun handle(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = device.name ?: result.scanRecord?.deviceName
        val hasAdvUuid = result.scanRecord?.serviceUuids?.any {
            it.uuid == OnePlusBluetoothUuids.Advertisement
        } == true
        val hint = sessionHint
        val candidate = OnePlusAdvCandidate.isCandidate(name, address, hint)
        val g6MarketingName = name?.startsWith("Dexcom", ignoreCase = true) == true
        // FEBC alone is OK for nameless G7 ADVs; reject FEBC+Dexcom* (G6 companions).
        val febcOk = hasAdvUuid && !g6MarketingName
        if (!candidate && !febcOk && !nameMatches(name)) return
        // When we have a sticky session, drop weak unrelated FEBC noise unless name/MAC matches.
        if (hint != null && !candidate && febcOk && !nameMatches(name)) return

        val hit = OnePlusScanResult(address = address, name = name, rssi = result.rssi)
            .apply { seenElapsedMs = SystemClock.elapsedRealtime() }
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
