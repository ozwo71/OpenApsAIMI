package app.aaps.plugins.dexcomoneplus.session

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
 * Session: pairing validation → GATT connect → KEKS auth → Control/EGV loop, with OEM
 * reconnect retries after GATT drop / connect-auth failure.
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
) : OnePlusBleSession {

    @Volatile
    private var up = false

    @Volatile
    private var running = false

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

        running = true
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.PAIRING, message = "pairing")
        emitWarmup()

        var attempt = 0
        while (running) {
            if (attempt > 0) {
                if (!reconnectPolicy.shouldRetry(attempt, profile)) {
                    fail("ONEPLUS_RECONNECT_EXHAUSTED: attempts=$attempt", fatal = false)
                    return
                }
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
                    "${OnePlusLogMarkers.RECONNECT}: attempt=0 " +
                        "maxRetries=${profile.connectRetryCount} timeoutMs=${profile.connectTimeoutMs}",
                )
            }

            val wantSessionStart = requestNewSensorStart && attempt == 0
            val outcome = runConnectionCycle(
                deviceAddress = deviceAddress,
                pairingCode = normalized,
                requestNewSensorStart = wantSessionStart,
                attempt = attempt,
            )
            if (!running) return

            when (outcome) {
                CycleOutcome.Stopped -> return
                CycleOutcome.EgvExited -> {
                    // Transient drop while still wanted — reconnect without SessionStop.
                    up = false
                    try {
                        gatt.disconnect()
                    } catch (_: Throwable) {
                    }
                    onSession(false, "egv_loop_exit")
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.SESSION}: down reason=egv_loop_exit → reconnect",
                    )
                    attempt++
                }
                CycleOutcome.RetryableFailure -> {
                    up = false
                    try {
                        gatt.disconnect()
                    } catch (_: Throwable) {
                    }
                    attempt++
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
        running = false
        up = false
        gatt.disconnect()
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE, message = reason)
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
    ): CycleOutcome {
        warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.PAIRING, message = "pairing_attempt_$attempt")
        emitWarmup()

        try {
            gatt.connect(deviceAddress)
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_GATT_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg attempt=$attempt", t)
            onError(msg, false)
            return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
        }

        if (!running) return CycleOutcome.Stopped

        val authResult = auth.authenticate(pairingCode)
        if (!authResult.ok) {
            val msg = authResult.message ?: "ONEPLUS_AUTH_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg attempt=$attempt")
            onError(msg, false)
            warmup = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.FAILED, message = msg)
            emitWarmup()
            return if (running) CycleOutcome.RetryableFailure else CycleOutcome.Stopped
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

        val egv = OnePlusEgvSession(
            gatt = gatt,
            onWarmup = { state ->
                warmup = state
                emitWarmup()
            },
            onGlucose = onGlucose,
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
            return CycleOutcome.RetryableFailure
        }

        return if (running) CycleOutcome.EgvExited else CycleOutcome.Stopped
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
        EgvExited,
        RetryableFailure,
        Stopped,
        Fatal,
    }
}
