package app.aaps.plugins.dexcomoneplus

import android.content.Context
import android.util.Log
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientAndroid
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerStub
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanListener
import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSession
import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSessionSkeleton
import app.aaps.plugins.dexcomoneplus.session.OnePlusSessionAuthKeks
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    override fun setContext(context: Context) {
        val app = context.applicationContext
        this.context = app
        scanner = OnePlusBleScannerAndroid(app)
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
        val gatt = OnePlusGattClientAndroid(ctx)
        val auth = OnePlusSessionAuthKeks(gatt)
        val created = OnePlusBleSessionSkeleton(
            gatt = gatt,
            auth = auth,
            onWarmup = { state -> watchers.forEach { it.onWarmup(state) } },
            onSession = { up, reason -> watchers.forEach { it.onSession(up, reason) } },
            onError = { message, fatal -> watchers.forEach { it.onError(message, fatal) } },
            onGlucose = { sample -> watchers.forEach { it.onGlucose(sample) } },
            // SessionStart only if transmitter has no session; never auto SessionStop.
            requestNewSensorStart = true,
        )
        session = created
        return created
    }

    private fun newBleExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "OnePlusBleExecutor").apply { isDaemon = true }
        }
}
