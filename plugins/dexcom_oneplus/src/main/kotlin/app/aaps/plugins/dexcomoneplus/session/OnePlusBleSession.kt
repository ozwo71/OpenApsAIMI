package app.aaps.plugins.dexcomoneplus.session

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClient
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientUnimplemented
import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.dexcomoneplus.reconnect.OemAwareReconnectPolicy
import app.aaps.plugins.dexcomoneplus.reconnect.OnePlusReconnectPolicy
import app.aaps.plugins.dexcomoneplus.warmup.OnePlusWarmupClock

/**
 * ONE+ BLE session lifecycle (scan already done → connect → auth → Control EGV → warm-up/glucose).
 *
 * ⚠️ ASYNC IMPACT: Real fills run on bleExecutor; [startWithPairingCode] blocks that thread
 * through KEKS + [OnePlusEgvSession] loop + reconnect sleeps. [stop] must be callable without
 * waiting for the executor queue (sets [running] false + GATT disconnect to unblock awaits).
 */
interface OnePlusBleSession {
    fun startWithPairingCode(deviceAddress: String, pairingCode: String)
    fun stop(reason: String?)
    fun isUp(): Boolean
    fun warmupState(): OnePlusWarmupState
}

/**
 * Outcome of the pre-connect step (`beforeConnect`).
 *
 * @param advFresh a recent ADV sighting for the target MAC is in hand (UI handoff or in-window
 *   rescan hit) → prefer a fast direct connect (`autoConnect=false`) over the OEM autoConnect park.
 */
data class OnePlusConnectPrep(val advFresh: Boolean = false)

/**
 * Pure decisions for the post-Control-loop transition, kept separate from Android BLE calls so the
 * normal duty-cycle behavior can be unit tested.
 */
internal object OnePlusBleSessionCyclePolicy {
    const val POST_COLLECTION_RECONNECT_ATTEMPT = 1

    fun waitForAdvertisementAfterExit(deliveredUsableGlucose: Boolean): Boolean =
        deliveredUsableGlucose

    fun applyFailureBudget(preparedPostCollectionAdvertisement: Boolean): Boolean =
        !preparedPostCollectionAdvertisement

    fun requireFreshAdvertisementBeforeReconnect(persistentAdvertisementMode: Boolean): Boolean =
        persistentAdvertisementMode

    fun allowConnection(restoredSessionMode: Boolean, advertisementFresh: Boolean): Boolean =
        !restoredSessionMode || advertisementFresh
}

/**
 * Session: pairing validation → GATT connect → KEKS auth → Control/EGV loop, with OEM
 * reconnect retries after GATT drop / connect-auth failure. After a successful EGV cycle, the
 * transmitter's disconnect is treated as normal duty cycling: wait persistently for its next ADV
 * instead of consuming the finite failure budget while the radio is asleep.
 *
 * Does **not** claim production BLE. Stub driver remains the default façade.
 */
class OnePlusBleSessionSkeleton(
    private val gatt: OnePlusGattClient = OnePlusGattClientUnimplemented(),
    private val auth: OnePlusSessionAuth = OnePlusSessionAuthUnimplemented(),
    private val reconnectPolicy: OnePlusReconnectPolicy = OemAwareReconnectPolicy(),
    private val profile: OemDeviceProfile = DeviceProfileRegistry.resolve(),
    private val onWarmup: (OnePlusWarmupState) -> Unit = {},
    private val onGlucose: (OnePlusGlucoseSample) -> Unit = {},
    private val onSession: (Boolean, String?) -> Unit = { _, _ -> },
    private val onError: (String, Boolean) -> Unit = { _, _ -> },
    /**
     * When true on the **first** successful connect only: SessionStart **if** the
     * transmitter has no session yet. Never auto SessionStop (Dexcom recovery).
     * Reconnect attempts always pass false.
     */
    private val requestNewSensorStart: Boolean = true,
    /**
     * Ob1-style pre-connect: LE rescan / handoff before [OnePlusGattClient.connect].
     * Runs on bleExecutor; must not touch UI. Returns [OnePlusConnectPrep] (ADV freshness).
     * Default no-op (tests / stub).
     */
    private val beforeConnect: (deviceAddress: String, attempt: Int) -> OnePlusConnectPrep =
        { _, _ -> OnePlusConnectPrep() },
    /** Juggluco-style reconnect: preload 16-byte KEKS shared key when available. */
    private val savedSharedKeyProvider: () -> ByteArray? = { null },
    /** Persist MAC + shared key after successful auth. */
    private val onAuthSuccess: (deviceAddress: String, sharedKey: ByteArray) -> Unit = { _, _ -> },
    /** Clear persisted shared key when short-auth / bond path is invalidated. */
    private val onAuthInvalidate: () -> Unit = {},
    /** Optional app context for PARTIAL_WAKE_LOCK during connect/auth (Juggluco). */
    private val appContext: Context? = null,
) : OnePlusBleSession {

    @Volatile
    private var up = false

    @Volatile
    private var running = false

    @Volatile
    private var cancelled = false

    private val lifecycleLock = Any()

    @Volatile
    private var warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)

    override fun startWithPairingCode(deviceAddress: String, pairingCode: String) {
        val codeError = OnePlusSessionStart.validationError(pairingCode)
        if (codeError != null) {
            fail(codeError, fatal = false)
            return
        }
        val normalized = OnePlusSessionStart.normalizePairingCode(pairingCode)
        if (deviceAddress.isBlank()) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: start pairing (addr blank) profile=${profile.id}",
            )
        } else {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: start pairing addr=${redactAddress(deviceAddress)} profile=${profile.id}",
            )
        }

        val startAllowed = synchronized(lifecycleLock) {
            if (cancelled) {
                false
            } else {
                running = true
                true
            }
        }
        if (!startAllowed) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: start ignored — session already stopped",
            )
            return
        }
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.PAIRING, message = "pairing")
        emitWarmup()

        var attempt = 0
        var preparedConnect: OnePlusConnectPrep? = null
        // A restored session must never fall back to a blind connect while the transmitter sleeps.
        // Explicit new-sensor setup may use its existing bounded initial-connect policy.
        var persistentAdvertisementMode = !requestNewSensorStart
        while (running) {
            if (attempt > 0) {
                val applyFailureBudget = OnePlusBleSessionCyclePolicy.applyFailureBudget(
                    preparedPostCollectionAdvertisement = preparedConnect != null,
                )
                if (applyFailureBudget && !reconnectPolicy.shouldRetry(attempt, profile)) {
                    fail("ONEPLUS_RECONNECT_EXHAUSTED: attempts=$attempt", fatal = false)
                    return
                }
                if (applyFailureBudget) {
                    val delayMs = reconnectPolicy.nextDelayMs(attempt, profile)
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.RECONNECT}: attempt=$attempt delayMs=$delayMs " +
                            "profile=${profile.id} aggressive=${profile.aggressiveReconnect}",
                    )
                    if (!sleepWhileRunning(delayMs)) {
                        stop("cancelled")
                        return
                    }
                } else {
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.RECONNECT}: fresh ADV ready — bypass retry delay " +
                            "attempt=$attempt profile=${profile.id}",
                    )
                }
            } else {
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.RECONNECT}: attempt=0 " +
                        "maxRetries=${profile.connectRetryCount} timeoutMs=${profile.connectTimeoutMs}",
                )
            }

            val wantSessionStart = OnePlusSessionStartPolicy.wantSessionStartOnAttempt(
                requestNewSensorStart = requestNewSensorStart,
                attempt = attempt,
            )
            val outcome = runConnectionCycle(
                deviceAddress = deviceAddress,
                pairingCode = normalized,
                requestNewSensorStart = wantSessionStart,
                attempt = attempt,
                preparedConnect = preparedConnect,
            )
            preparedConnect = null
            if (!running) return

            when (outcome) {
                CycleOutcome.Stopped -> return
                CycleOutcome.EgvDeliveredThenExited -> {
                    persistentAdvertisementMode = true
                    preparedConnect = preparePostCollectionReconnect(
                        deviceAddress = deviceAddress,
                        reason = "egv_cycle_complete_waiting_for_adv",
                    )
                    if (!running || preparedConnect == null) return
                    // Attempt 1 suppresses SessionStart and reports a reconnect, while the prepared
                    // fresh ADV bypasses both the backoff delay and a second pre-connect scan.
                    attempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT
                }
                CycleOutcome.EgvExited -> {
                    if (OnePlusBleSessionCyclePolicy.requireFreshAdvertisementBeforeReconnect(
                            persistentAdvertisementMode,
                        )
                    ) {
                        preparedConnect = preparePostCollectionReconnect(
                            deviceAddress = deviceAddress,
                            reason = "egv_loop_exit_waiting_for_adv",
                        )
                        if (!running || preparedConnect == null) return
                        attempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT
                    } else {
                        // Drop before the first usable EGV: retain bounded failure retries.
                        up = false
                        try {
                            gatt.disconnect()
                        } catch (_: Throwable) {
                        }
                        onSession(false, "egv_loop_exit")
                        enterReconnecting("egv_loop_exit")
                        Log.i(
                            OnePlusLogMarkers.TAG,
                            "${OnePlusLogMarkers.SESSION}: down reason=egv_loop_exit → reconnect",
                        )
                        attempt++
                    }
                }
                CycleOutcome.RetryableFailure -> {
                    if (OnePlusBleSessionCyclePolicy.requireFreshAdvertisementBeforeReconnect(
                            persistentAdvertisementMode,
                        )
                    ) {
                        preparedConnect = preparePostCollectionReconnect(
                            deviceAddress = deviceAddress,
                            reason = "cycle_failure_waiting_for_adv",
                        )
                        if (!running || preparedConnect == null) return
                        attempt = OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT
                    } else {
                        up = false
                        try {
                            gatt.disconnect()
                        } catch (_: Throwable) {
                        }
                        attempt++
                    }
                }
                CycleOutcome.Fatal -> return
            }
        }
    }

    /**
     * Safe to call from any thread. Clears [running] and disconnects GATT so Control awaits unblock.
     *
     * ⚠️ ASYNC IMPACT: Must not be queued behind [startWithPairingCode] on the same single-thread
     * executor alone — callers should invoke [stop] directly (see [OnePlusCgmDriverReal.disconnect]).
     */
    override fun stop(reason: String?) {
        synchronized(lifecycleLock) {
            cancelled = true
            running = false
            up = false
            warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE, message = reason)
        }
        gatt.disconnect()
        emitWarmup()
        onSession(false, reason)
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: down reason=$reason")
    }

    override fun isUp(): Boolean = up

    override fun warmupState(): OnePlusWarmupState = warmup

    private fun runConnectionCycle(
        deviceAddress: String,
        pairingCode: String,
        requestNewSensorStart: Boolean,
        attempt: Int,
        preparedConnect: OnePlusConnectPrep?,
    ): CycleOutcome {
        // CONNECTING (first attempt) / RECONNECTING (retry) — never PAIRING here, so the UI can tell
        // "establishing link" from the terminal FAILED it used to flash between retries.
        val connectingPhase =
            if (attempt == 0) OnePlusWarmupState.Phase.CONNECTING else OnePlusWarmupState.Phase.RECONNECTING
        warmup = OnePlusWarmupState(phase = connectingPhase, message = "connect_attempt_$attempt")
        emitWarmup()

        val prep = preparedConnect ?: try {
            beforeConnect(deviceAddress, attempt)
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_BEFORE_CONNECT_FAILED"
            Log.e(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.ERROR}: beforeConnect failed: $msg attempt=$attempt",
            )
            onError(msg, false)
            enterReconnecting(msg)
            return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
        }
        if (!running) return CycleOutcome.Stopped
        if (!OnePlusBleSessionCyclePolicy.allowConnection(
                restoredSessionMode = !this.requestNewSensorStart,
                advertisementFresh = prep.advFresh,
            )
        ) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: restored session requires fresh ADV — remain waiting",
            )
            enterReconnecting("waiting_for_next_adv")
            return CycleOutcome.RetryableFailure
        }

        // Fresh ADV in hand → fast direct connect (fail-fast status 133 → quick retry). Otherwise fall
        // back to the OEM autoConnect policy (Samsung parks a background connect when the sensor is
        // quiet — that park cost ~48 s in the field log; the handoff avoids it when a sighting exists).
        val useAutoConnect = when {
            prep.advFresh -> false
            profile.autoConnectFromAttempt < 0 -> false
            else -> attempt >= profile.autoConnectFromAttempt
        }
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: connect decision advFresh=${prep.advFresh} " +
                "autoConnect=$useAutoConnect attempt=$attempt profile=${profile.id}",
        )
        try {
            gatt.connect(deviceAddress, autoConnect = useAutoConnect)
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_GATT_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg attempt=$attempt", t)
            onError(msg, false)
            enterReconnecting(msg)
            return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
        }

        if (!running) return CycleOutcome.Stopped

        val wakeLock = acquireAuthWakeLock()
        try {
            val authResult = auth.authenticate(pairingCode, savedSharedKeyProvider())
            if (!running) return CycleOutcome.Stopped
            if (!authResult.ok) {
                val msg = authResult.message ?: "ONEPLUS_AUTH_FAILED"
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg attempt=$attempt")
                if (authResult.invalidateSharedKey ||
                    msg.contains("bond failure", ignoreCase = true) ||
                    msg.contains("key refresh", ignoreCase = true) ||
                    msg.contains("Missing QR", ignoreCase = true) ||
                    msg.contains("short-auth rejected", ignoreCase = true)
                ) {
                    // Stale shared key / cert path — force full KEKS next attempt (Juggluco resetCerts).
                    try {
                        onAuthInvalidate()
                    } catch (_: Throwable) {
                    }
                }
                onError(msg, false)
                // Retryable: the loop will re-attempt (short-auth reject wipes the key → full J-PAKE
                // next). Show RECONNECTING, NOT FAILED — the old FAILED flash made the user abandon
                // during the reconnect delay (field log 23:18:40).
                enterReconnecting(msg)
                return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
            }

            authResult.sharedKey?.let { key ->
                try {
                    onAuthSuccess(deviceAddress, key)
                } catch (t: Throwable) {
                    Log.w(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.SESSION}: onAuthSuccess persist failed: ${t.message}",
                    )
                }
            }

            // Do NOT invent a 30 min WARMING clock here — that blocked ingest even when the
            // transmitter was already producing Ok EGVs. Protocol (TransmitterTime / EGV) sets phase.
            warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.PAIRING, message = "auth_ok")
            up = true
            emitWarmup()
            onSession(true, if (attempt == 0) "session_up" else "session_reconnected")
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: up attempt=$attempt newStart=$requestNewSensorStart — Control/EGV",
            )

            var deliveredUsableGlucose = false
            val egv = OnePlusEgvSession(
                gatt = gatt,
                onWarmup = { state ->
                    warmup = state
                    emitWarmup()
                },
                onGlucose = { sample ->
                    deliveredUsableGlucose = true
                    onGlucose(sample)
                },
                onError = onError,
                requestNewSensorStart = requestNewSensorStart,
            )
            try {
                egv.run(shouldContinue = { running && gatt.isConnected() })
            } catch (t: Throwable) {
                if (!running) return CycleOutcome.Stopped
                val msg = t.message ?: "ONEPLUS_CONTROL_FAILED"
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
                onError(msg, false)
                enterReconnecting(msg)
                return CycleOutcome.RetryableFailure
            }

            return when {
                !running -> CycleOutcome.Stopped
                OnePlusBleSessionCyclePolicy.waitForAdvertisementAfterExit(deliveredUsableGlucose) ->
                    CycleOutcome.EgvDeliveredThenExited
                else -> CycleOutcome.EgvExited
            }
        } finally {
            // Juggluco holds wake for the whole connection; we keep it through first EGV cycle.
            releaseWakeLock(wakeLock)
        }
    }

    private fun acquireAuthWakeLock(): PowerManager.WakeLock? {
        val ctx = appContext ?: return null
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenApsAIMI::DexcomOnePlusAuth").apply {
                setReferenceCounted(false)
                acquire(AUTH_WAKE_LOCK_MS)
            }
        } catch (t: Throwable) {
            Log.w(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: wakeLock ${t.message}")
            null
        }
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        if (lock == null) return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Throwable) {
        }
    }

    /**
     * Transient / retryable transition. UI shows RECONNECTING (never terminal FAILED) so the user
     * keeps waiting through the reconnect delay. Terminal failure stays in [fail].
     */
    private fun enterReconnecting(message: String?) {
        if (!running) return
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.RECONNECTING, message = message)
        emitWarmup()
    }

    /**
     * Ends the current successful duty cycle and waits for the next transmitter radio window.
     * Watcher exceptions are isolated so they cannot terminate the persistent BLE loop.
     */
    private fun preparePostCollectionReconnect(
        deviceAddress: String,
        reason: String,
    ): OnePlusConnectPrep? {
        if (!running) return null
        up = false
        try {
            gatt.disconnect()
        } catch (_: Throwable) {
        }
        if (!running) return null
        try {
            onSession(false, reason)
        } catch (t: Throwable) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: down callback failed: ${t.message}",
            )
        }
        if (!running) return null
        try {
            enterReconnecting("waiting_for_next_adv")
        } catch (t: Throwable) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.WARMUP}: waiting callback failed: ${t.message}",
            )
        }
        if (!running) return null
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: cycle down reason=$reason — wait persistently for next ADV",
        )
        return awaitFreshAdvertisement(deviceAddress)
    }

    /**
     * A successful EGV cycle resets failure semantics. Dexcom advertises only in short windows, so
     * repeatedly scan until the target MAC is actually visible and hand that fresh sighting directly
     * to the next connection cycle. No blind GATT connect and no finite retry exhaustion here.
     *
     * ⚠️ ASYNC IMPACT: blocks bleExecutor in bounded scan calls; [stop] clears [running], so this
     * loop exits as soon as the current bounded pre-connect scan returns.
     */
    private fun awaitFreshAdvertisement(deviceAddress: String): OnePlusConnectPrep? {
        while (running) {
            val prep = try {
                beforeConnect(
                    deviceAddress,
                    OnePlusBleSessionCyclePolicy.POST_COLLECTION_RECONNECT_ATTEMPT,
                )
            } catch (t: Throwable) {
                Log.w(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: waiting for next ADV failed: ${t.message}",
                )
                OnePlusConnectPrep(advFresh = false)
            }
            if (!running) return null
            if (prep.advFresh) {
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SCAN}: next-cycle target ADV acquired",
                )
                return prep
            }
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SCAN}: target ADV absent — remain waiting",
            )
            if (!sleepWhileRunning(ADV_WAIT_RESTART_DELAY_MS)) return null
        }
        return null
    }

    private fun fail(message: String, fatal: Boolean) {
        running = false
        up = false
        try {
            gatt.disconnect()
        } catch (_: Throwable) {
        }
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.FAILED, message = message)
        emitWarmup()
        onError(message, fatal)
        onSession(false, message)
        Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $message fatal=$fatal")
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: down reason=fail")
    }

    private fun emitWarmup() {
        val remaining = OnePlusWarmupClock.resolveRemainingMs(warmup, System.currentTimeMillis())
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.WARMUP}: phase=${warmup.phase} remainingMs=$remaining msg=${warmup.message}",
        )
        onWarmup(warmup)
    }

    /** Interruptible sleep that exits early when [stop] clears [running]. */
    private fun sleepWhileRunning(delayMs: Long): Boolean {
        if (delayMs <= 0L) return running
        val end = System.currentTimeMillis() + delayMs
        while (running && System.currentTimeMillis() < end) {
            val slice = (end - System.currentTimeMillis()).coerceAtMost(200L).coerceAtLeast(1L)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return running
    }

    private fun redactAddress(address: String): String {
        if (address.length <= 5) return "***"
        return "***" + address.takeLast(5)
    }

    private enum class CycleOutcome {
        EgvDeliveredThenExited,
        EgvExited,
        RetryableFailure,
        Stopped,
        Fatal,
    }

    companion object {
        /**
         * Cover KEKS + bond prompt + first Control/EGV cycle.
         * Juggluco uses an unbounded wake lock for the whole connection; we bound it.
         */
        private const val AUTH_WAKE_LOCK_MS = 600_000L
        private const val ADV_WAIT_RESTART_DELAY_MS = 250L
    }
}
