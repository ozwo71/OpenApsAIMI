package app.aaps.plugins.dexcomoneplus.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
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
 * The discovery scan remains unfiltered (Eversense-style) so nameless transmitters still appear.
 * The known-MAC pre-connect wait uses a platform [ScanFilter], allowing delivery while the screen
 * is off on Android/Samsung.
 *
 * ⚠️ ASYNC IMPACT: [OnePlusScanListener.onDevice] on binder thread — hop to main for Compose.
 */
@SuppressLint("MissingPermission")
class OnePlusBleScannerAndroid(
    context: Context,
    /** Optional stored session for Juggluco-style candidate ranking / sticky match. */
    @Volatile var sessionHint: OnePlusStoredSession? = null,
    /** Sensor slot owning this scanner (`prod` / `staging`) — logged so field traces are attributable. */
    private val slot: String = OnePlusLogMarkers.SLOT_PRODUCTION,
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
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: [$slot] failed errorCode=$errorCode")
            scanning.set(false)
        }
    }

    override fun startScan(listener: OnePlusScanListener) {
        val adapter = bluetoothManager.adapter
        val leScanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || leScanner == null) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: [$slot] adapter unavailable")
            return
        }
        stopScan()
        seen.clear()
        this.listener = listener
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // UI discovery runs on the main thread: book the platform quota slot but never wait for one
        // (the reserve kept by OnePlusScanBudget is precisely what makes this always affordable).
        OnePlusScanBudget.record(SystemClock.elapsedRealtime())
        leScanner.startScan(null, settings, callback)
        scanning.set(true)
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: [$slot] started")
    }

    override fun stopScan() {
        val leScanner = bluetoothManager.adapter?.bluetoothLeScanner
        try {
            leScanner?.stopScan(callback)
        } catch (_: Throwable) {
        }
        scanning.set(false)
        listener = null
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: [$slot] stopped")
    }

    override fun isScanning(): Boolean = scanning.get()

    /**
     * Pre-connect ADV wait isolated from UI [stopScan] (own [ScanCallback]).
     * Field log: StartActivity onDispose stopped shared scan ~0.6s into prepareConnect.
     *
     * The platform filter list is OR'ed: the target MAC **plus** the ONE+/G7 advertisement service
     * (FEBC). The extra filter costs no additional scan start, and lets a wait that never hears its
     * own MAC still report that another ONE+ is nearby — the stale-stored-MAC signature.
     *
     * ⚠️ ASYNC IMPACT: blocks the calling BLE executor for up to [timeoutMs] plus any wait imposed
     * by [OnePlusScanBudget]; [OnePlusBleSession.stop] does not need this to return (it clears
     * `running` and disconnects GATT).
     */
    override fun awaitTarget(address: String, timeoutMs: Long): OnePlusAdvWaitResult {
        val adapter = bluetoothManager.adapter
        val leScanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || leScanner == null) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget adapter unavailable")
            return OnePlusAdvWaitResult()
        }
        val target = normalizeTargetAddress(address)
        val found = AtomicReference<OnePlusScanResult?>(null)
        val foreign = ConcurrentHashMap<String, OnePlusScanResult>()
        val latch = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val addr = device.address ?: return
                val name = device.name ?: result.scanRecord?.deviceName
                if (addr.uppercase() != target) {
                    // FEBC-matched neighbour: keep it only if it looks like a G7-family transmitter
                    // (never a G6 `Dexcom*` companion), so the diagnostic cannot cry wolf.
                    if (isG7FamilyAdvertisement(name)) {
                        foreign[addr.uppercase()] = OnePlusScanResult(addr, name, result.rssi)
                            .apply { seenElapsedMs = SystemClock.elapsedRealtime() }
                    }
                    return
                }
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
                    "${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget failed errorCode=$errorCode",
                )
                latch.countDown()
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        awaitScanBudgetSlot()
        return try {
            val targetFilter = ScanFilter.Builder()
                .setDeviceAddress(target)
                .build()
            val familyFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(OnePlusBluetoothUuids.Advertisement))
                .build()
            leScanner.startScan(listOf(targetFilter, familyFilter), settings, cb)
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget filtered started " +
                    "mac=***${target.takeLast(5)} timeoutMs=$timeoutMs",
            )
            latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            OnePlusAdvWaitResult(target = found.get(), foreign = foreign.values.toList())
        } catch (t: Throwable) {
            Log.w(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget ${t.message}")
            OnePlusAdvWaitResult(foreign = foreign.values.toList())
        } finally {
            try {
                leScanner.stopScan(cb)
            } catch (_: Throwable) {
            }
            Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget stopped")
        }
    }

    /**
     * Hold this (background) thread until the shared [OnePlusScanBudget] grants a start slot, so
     * two slots waiting for their sensors cannot together trip the platform's silent scan throttle.
     * Returns with the slot already booked.
     */
    private fun awaitScanBudgetSlot() {
        var logged = false
        while (true) {
            val wait = OnePlusScanBudget.reserve(SystemClock.elapsedRealtime())
            if (wait <= 0L) return
            if (!logged) {
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: [$slot] scan budget full — deferring startScan ${wait}ms",
                )
                logged = true
            }
            try {
                Thread.sleep(wait.coerceAtMost(OnePlusScanBudget.MAX_SLEEP_SLICE_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
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
                "${OnePlusLogMarkers.SCAN}: [$slot] device name=${name ?: "?"} rssi=${hit.rssi} " +
                    "score=$score addr=***${address.takeLast(5)}",
            )
            listener?.onDevice(hit)
        }
    }

    companion object {
        fun nameMatches(name: String?): Boolean = OnePlusAdvCandidate.nameMatchesSoft(name)

        internal fun normalizeTargetAddress(address: String): String = address.uppercase()

        /**
         * A FEBC advertiser that is a G7-family transmitter: either a nameless ADV (G7/ONE+ often
         * advertise without a local name) or a `DXCM`/`DX0x` name — never a G6 `Dexcom*` companion.
         */
        internal fun isG7FamilyAdvertisement(name: String?): Boolean = when {
            name.isNullOrBlank()                            -> true
            name.startsWith("Dexcom", ignoreCase = true)    -> false
            else                                            -> nameMatches(name)
        }
    }
}
