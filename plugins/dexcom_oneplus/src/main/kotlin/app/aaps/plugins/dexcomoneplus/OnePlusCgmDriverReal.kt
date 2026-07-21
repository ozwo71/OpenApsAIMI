package app.aaps.plugins.dexcomoneplus

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientAndroid
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerStub
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanListener
import app.aaps.plugins.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSession
import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSessionSkeleton
import app.aaps.plugins.dexcomoneplus.session.OnePlusConnectPrep
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

    @Volatile
    private var profile: OemDeviceProfile = DeviceProfileRegistry.GenericFallback

    @Volatile
    private var sensorStore: OnePlusSensorStore? = null

    /** Last ADV name seen for [connect] target — persisted on auth success. */
    @Volatile
    private var pendingDeviceName: String? = null

    /**
     * `SystemClock.elapsedRealtime()` of the ADV sighting handed off from the UI for the [connect]
     * target (0 = none). Lets [prepareConnect] skip the blind re-scan when the sensor was just seen.
     */
    @Volatile
    private var pendingAdvSightingElapsedMs: Long = 0L

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
        // Stop any in-flight reconnect loop (may still be targeting a previous MAC).
        session?.stop("superseded")
        sensorStore?.saveIdentity(
            OnePlusSensorIdentity(
                pin = pairingCode,
                serial = sensorStore?.load()?.identity?.serial,
                gtin = sensorStore?.load()?.identity?.gtin,
                rawGs1 = sensorStore?.load()?.identity?.rawGs1,
            ),
        )
        sensorStore?.saveLastMac(deviceAddress)
        // Fresh (re)pairing from the UI: drop any stale KEKS shared key so attempt 0 runs full
        // J-PAKE instead of a short-auth that the transmitter rejects (field: stale preload →
        // auth=2 → a wasted connect cycle + FAILED). A successful auth re-persists a new key, so
        // silent reconnects inside the session loop still short-auth.
        sensorStore?.clearSharedKey()
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

    /**
     * UI entry point: hands off the freshest ADV [sighting] (name + `seenElapsedMs`) so
     * [prepareConnect] can connect in-window without a blind re-scan.
     */
    fun connect(
        deviceAddress: String,
        pairingCode: String,
        sighting: OnePlusScanResult?,
    ) {
        val name = sighting?.name
        pendingDeviceName = name
        pendingAdvSightingElapsedMs = sighting?.seenElapsedMs ?: 0L
        if (!name.isNullOrBlank()) {
            sensorStore?.saveLastDeviceName(name)
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
     * Ob1 / Samsung recovery before the GATT `connect`, returning [OnePlusConnectPrep]
     * (whether a **fresh ADV** is in hand → session does a fast direct connect).
     *
     * 1. **Handoff fast-path:** if the UI (or a prior in-window rescan) saw this MAC within
     *    [ADV_HANDOFF_FRESH_MS], connect immediately — no blind re-scan. This is what removes the
     *    ~8 s wasted scan + the ~48 s autoConnect park seen in the field log.
     * 2. Otherwise LE-scan via [OnePlusBleScanner.awaitTargetMac] (private ScanCallback so UI
     *    [stopScan] cannot cancel it). A hit → fresh; a miss with
     *    [OemDeviceProfile.requireAdvBeforeConnect] on attempt 0 (and no UI selection) → defer.
     *
     * ⚠️ ASYNC IMPACT: blocks bleExecutor; binder delivers ADV into awaitTargetMac.
     */
    private fun prepareConnect(deviceAddress: String, attempt: Int): OnePlusConnectPrep {
        val target = deviceAddress.uppercase()
        val sightingAgeMs =
            if (pendingAdvSightingElapsedMs > 0L) {
                SystemClock.elapsedRealtime() - pendingAdvSightingElapsedMs
            } else {
                Long.MAX_VALUE
            }
        if (sightingAgeMs in 0..ADV_HANDOFF_FRESH_MS) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: pre-connect handoff — fresh ADV ${sightingAgeMs}ms old, " +
                    "skip rescan (attempt=$attempt mac=***${target.takeLast(5)})",
            )
            return OnePlusConnectPrep(advFresh = true)
        }

        val scanMs = profile.preConnectScanMs
        if (scanMs <= 0L) return OnePlusConnectPrep(advFresh = false)
        // UI Connect just selected this ADV → don't hard-fail if re-scan misses briefly.
        val uiJustSelected = !pendingDeviceName.isNullOrBlank()
        val hardRequireAdv =
            profile.requireAdvBeforeConnect && attempt == 0 && !uiJustSelected
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SCAN}: pre-connect awaitTargetMac ${scanMs}ms attempt=$attempt " +
                "requireAdv=${profile.requireAdvBeforeConnect} hardRequire=$hardRequireAdv " +
                "uiJustSelected=$uiJustSelected",
        )
        try {
            val hit = scanner.awaitTargetMac(target, scanMs)
            if (hit != null) {
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: pre-connect ADV name=${hit.name} " +
                        "rssi=${hit.rssi} mac=***${target.takeLast(5)}",
                )
                if (!hit.name.isNullOrBlank()) pendingDeviceName = hit.name
                return OnePlusConnectPrep(advFresh = true)
            }
            if (hardRequireAdv) {
                throw IllegalStateException(
                    "ONEPLUS_SCAN: ADV not seen in ${scanMs}ms — defer connect (keep screen on, sensor close)",
                )
            }
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: pre-connect ADV not seen in ${scanMs}ms — connect anyway " +
                    "(attempt=$attempt known MAC uiJustSelected=$uiJustSelected)",
            )
            return OnePlusConnectPrep(advFresh = false)
        } catch (t: IllegalStateException) {
            throw t
        } catch (t: Throwable) {
            Log.w(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SCAN}: pre-connect ${t.message}")
            if (hardRequireAdv) {
                throw IllegalStateException(
                    "ONEPLUS_SCAN: pre-connect failed — ${t.message}",
                    t,
                )
            }
            return OnePlusConnectPrep(advFresh = false)
        }
    }

    private fun newBleExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "OnePlusBleExecutor").apply { isDaemon = true }
        }

    private companion object {
        /**
         * Max age of a handed-off ADV sighting still treated as "fresh" (skip rescan, direct
         * connect). G7/ONE+ advertises in short windows, so a sighting older than this may already
         * be quiet and a direct connect would miss the window.
         */
        const val ADV_HANDOFF_FRESH_MS = 6_000L
    }
}
