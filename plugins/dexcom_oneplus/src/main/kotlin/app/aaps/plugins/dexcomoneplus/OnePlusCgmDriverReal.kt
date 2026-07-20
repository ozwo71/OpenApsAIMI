package app.aaps.plugins.dexcomoneplus

import android.content.Context
import android.util.Log
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientAndroid
import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerStub
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanListener
import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSession
import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSessionSkeleton
import app.aaps.plugins.dexcomoneplus.session.OnePlusSessionAuthKeks
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real driver: LE scan + GATT + KEKS + Control EGV loop.
 *
 * Still requires device A3 confirmation before any production claim.
 *
 * ⚠️ ASYNC IMPACT: scan on binder; connect/auth/EGV on [bleExecutor] (blocks that thread).
 * [disconnect] / [shutdown] call [OnePlusBleSession.stop] directly so GATT disconnect can
 * unblock [OnePlusGattClient.awaitControlNotify]. Watchers may be off main.
 */
class OnePlusCgmDriverReal : OnePlusCgmDriver {

    private val watchers = CopyOnWriteArrayList<OnePlusGlucoseWatcher>()
    private var context: Context? = null

    @Volatile
    private var bleExecutor: ExecutorService = newBleExecutor()

    @Volatile
    private var scanner: OnePlusBleScanner = OnePlusBleScannerStub()

    @Volatile
    private var session: OnePlusBleSession? = null

    @Volatile
    private var profile: OemDeviceProfile = DeviceProfileRegistry.GenericFallback

    @Volatile
    private var sensorStore: OnePlusSensorStore? = null

    /** Last ADV name seen for [connect] target — persisted on auth success. */
    @Volatile
    private var pendingDeviceName: String? = null

    override fun setContext(context: Context) {
        val app = context.applicationContext
        this.context = app
        val store = OnePlusSensorStore(app)
        sensorStore = store
        scanner = OnePlusBleScannerAndroid(app, sessionHint = store.load())
        profile = DeviceProfileRegistry.resolve()
    }

    fun sensorStore(): OnePlusSensorStore? = sensorStore

    fun saveIdentity(identity: OnePlusSensorIdentity) {
        sensorStore?.saveIdentity(identity)
        (scanner as? OnePlusBleScannerAndroid)?.sessionHint = sensorStore?.load()
    }

    override fun addWatcher(watcher: OnePlusGlucoseWatcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher)
    }

    override fun removeWatcher(watcher: OnePlusGlucoseWatcher) {
        watchers.remove(watcher)
    }

    override fun startScan(listener: OnePlusScanListener) {
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: start")
        scanner.startScan(listener)
    }

    override fun stopScan() {
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: stop")
        scanner.stopScan()
    }

    override fun connect(deviceAddress: String, pairingCode: String) {
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: connect requested (Real GATT+KEKS+EGV)",
        )
        scanner.stopScan()
        sensorStore?.saveIdentity(
            OnePlusSensorIdentity(
                pin = pairingCode,
                serial = sensorStore?.load()?.identity?.serial,
                gtin = sensorStore?.load()?.identity?.gtin,
                rawGs1 = sensorStore?.load()?.identity?.rawGs1,
            ),
        )
        sensorStore?.saveLastMac(deviceAddress)
        (scanner as? OnePlusBleScannerAndroid)?.sessionHint = sensorStore?.load()
        bleExecutor.execute {
            try {
                ensureSession().startWithPairingCode(deviceAddress, pairingCode)
            } catch (t: Throwable) {
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: ${t.message}", t)
                watchers.forEach {
                    it.onError(t.message ?: "ONEPLUS_CONNECT_FAILED", fatal = false)
                    it.onWarmup(
                        OnePlusWarmupState(
                            phase = OnePlusWarmupState.Phase.FAILED,
                            message = t.message,
                        ),
                    )
                }
            }
        }
    }

    fun connect(
        deviceAddress: String,
        pairingCode: String,
        advertisedName: String?,
    ) {
        pendingDeviceName = advertisedName
        if (!advertisedName.isNullOrBlank()) {
            sensorStore?.saveLastDeviceName(advertisedName)
        }
        connect(deviceAddress, pairingCode)
    }

    override fun disconnect() {
        // Must not queue behind a blocking Control/EGV loop on bleExecutor.
        session?.stop("disconnect")
    }

    override fun shutdown() {
        try {
            scanner.stopScan()
        } catch (_: Throwable) {
        }
        try {
            session?.stop("shutdown")
        } catch (_: Throwable) {
        }
        session = null
        watchers.clear()
        bleExecutor.shutdownNow()
        bleExecutor = newBleExecutor()
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: shutdown")
    }

    override fun warmupState(): OnePlusWarmupState =
        session?.warmupState() ?: OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)

    override fun isSessionUp(): Boolean = session?.isUp() == true

    private fun ensureSession(): OnePlusBleSession {
        session?.let { return it }
        val ctx = context ?: error("ONEPLUS_SESSION: setContext required")
        val gatt = OnePlusGattClientAndroid(ctx, profile)
        val auth = OnePlusSessionAuthKeks(gatt)
        val store = sensorStore
        val created = OnePlusBleSessionSkeleton(
            gatt = gatt,
            auth = auth,
            profile = profile,
            onWarmup = { state -> watchers.forEach { it.onWarmup(state) } },
            onSession = { up, reason -> watchers.forEach { it.onSession(up, reason) } },
            onError = { message, fatal -> watchers.forEach { it.onError(message, fatal) } },
            onGlucose = { sample -> watchers.forEach { it.onGlucose(sample) } },
            // SessionStart only if transmitter has no session; never auto SessionStop.
            requestNewSensorStart = true,
            beforeConnect = { address, attempt -> prepareConnect(address, attempt) },
            savedSharedKeyProvider = { store?.load()?.sharedKey },
            onAuthSuccess = { address, key ->
                store?.saveLastMac(address)
                store?.saveSharedKey(key)
                pendingDeviceName?.let { store?.saveLastDeviceName(it) }
                (scanner as? OnePlusBleScannerAndroid)?.sessionHint = store?.load()
            },
            onAuthInvalidate = {
                store?.clearSharedKey()
                (scanner as? OnePlusBleScannerAndroid)?.sessionHint = store?.load()
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: cleared persisted KEKS shared key",
                )
            },
            appContext = ctx,
        )
        session = created
        return created
    }

    /**
     * Ob1 recovery: on retries, LE-scan until the target MAC advertises (or timeout).
     * Attempt 0 relies on UI scan + GATT [scanHandoffMs].
     *
     * ⚠️ ASYNC IMPACT: blocks bleExecutor; scan callbacks on binder trip the latch.
     */
    private fun prepareConnect(deviceAddress: String, attempt: Int) {
        val scanMs = profile.preConnectScanMs
        if (attempt <= 0 || scanMs <= 0L) return
        val target = deviceAddress.uppercase()
        val seen = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SCAN}: pre-connect rescan ${scanMs}ms attempt=$attempt",
        )
        try {
            val hint = sensorStore?.load()
            scanner.startScan { hit ->
                val macMatch = hit.address.uppercase() == target
                val nameMatch = OnePlusAdvCandidate.isCandidate(hit.name, hit.address, hint)
                if (macMatch || (nameMatch && hint?.lastMac.isNullOrBlank())) {
                    if (seen.compareAndSet(false, true)) {
                        Log.i(
                            OnePlusLogMarkers.TAG,
                            "${OnePlusLogMarkers.SCAN}: pre-connect ADV name=${hit.name} rssi=${hit.rssi}",
                        )
                        if (!hit.name.isNullOrBlank()) pendingDeviceName = hit.name
                        latch.countDown()
                    }
                }
            }
            val found = latch.await(scanMs, TimeUnit.MILLISECONDS)
            if (!found) {
                Log.w(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: pre-connect ADV not seen in ${scanMs}ms — connect anyway",
                )
            }
        } catch (t: Throwable) {
            Log.w(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: pre-connect ${t.message}")
        } finally {
            try {
                scanner.stopScan()
            } catch (_: Throwable) {
            }
        }
    }

    private fun newBleExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "OnePlusBleExecutor").apply { isDaemon = true }
        }
}
