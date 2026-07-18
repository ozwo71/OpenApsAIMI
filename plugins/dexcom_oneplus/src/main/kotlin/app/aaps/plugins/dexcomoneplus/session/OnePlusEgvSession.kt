package app.aaps.plugins.dexcomoneplus.session

import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClient
import app.aaps.plugins.dexcomoneplus.parse.OnePlusCalibrationMapper
import app.aaps.plugins.dexcomoneplus.parse.OnePlusCalibrationState
import app.aaps.plugins.dexcomoneplus.parse.OnePlusEGlucoseTx
import app.aaps.plugins.dexcomoneplus.parse.OnePlusGlucoseParser
import app.aaps.plugins.dexcomoneplus.parse.OnePlusSessionStartRx
import app.aaps.plugins.dexcomoneplus.parse.OnePlusSessionStartTx
import app.aaps.plugins.dexcomoneplus.parse.OnePlusSessionStopRx
import app.aaps.plugins.dexcomoneplus.parse.OnePlusSessionStopTx
import app.aaps.plugins.dexcomoneplus.parse.OnePlusTransmitterTimeRx
import app.aaps.plugins.dexcomoneplus.parse.OnePlusTransmitterTimeTx
import app.aaps.plugins.dexcomoneplus.warmup.OnePlusWarmupClock

/**
 * Post-KEKS Control / EGV path (xDrip Ob1 `GET_DATA`).
 *
 * Sequence: enable Control indications → TransmitterTime (0x24) → optional
 * SessionStop (0x28) if restarting an active session → SessionStart (0x26) →
 * short BackFill (0x59) → write [OnePlusEGlucoseTx] (0x4e) → parse EGV1/EGV2.
 *
 * ⚠️ ASYNC IMPACT: Blocks the calling thread (intended: single bleExecutor).
 * [shouldContinue] must flip false and [OnePlusGattClient.disconnect] must run
 * (typically from [OnePlusBleSession.stop]) so [OnePlusGattClient.awaitControlNotify]
 * unblocks. Do not call AIMI / UI work from this loop; only invoke callbacks.
 *
 * Provenance: Ob1G5StateMachine.doGetData + SessionStart/EGlucose at pin
 * `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (GPL-3.0).
 */
class OnePlusEgvSession(
    private val gatt: OnePlusGattClient,
    private val onWarmup: (OnePlusWarmupState) -> Unit = {},
    private val onGlucose: (OnePlusGlucoseSample) -> Unit = {},
    private val onError: (String, Boolean) -> Unit = { _, _ -> },
    /**
     * When true and transmitter has **no** session: send SessionStartTx (0x26).
     * Never auto SessionStop. Also sent once if EGV reports Stopped / SensorStopped
     * (sensor already stopped — Start is non-destructive).
     */
    private val requestNewSensorStart: Boolean = false,
) {

    @Volatile
    private var sessionStartAttempted = false

    /** Last transmitter time (seconds). Filled by TransmitterTimeRx / SessionStartRx. */
    @Volatile
    private var lastDexTimeSeconds: Int = 0

    /** True when TransmitterTimeRx reported an active sensor session. */
    @Volatile
    private var sessionAlreadyInProgress: Boolean = false

    /**
     * Continuous Control/EGV loop until [shouldContinue] is false or GATT drops.
     *
     * @param notifyTimeoutMs per-wait for Control indication (Ob1 uses ~10s)
     * @param rewriteIntervalMs re-issue EGlucoseTx after idle
     */
    fun run(
        shouldContinue: () -> Boolean,
        notifyTimeoutMs: Long = DEFAULT_NOTIFY_TIMEOUT_MS,
        rewriteIntervalMs: Long = DEFAULT_REWRITE_INTERVAL_MS,
    ) {
        if (!gatt.isConnected()) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: EGV GATT not connected")
            onError("ONEPLUS_EGV_NOT_CONNECTED", false)
            return
        }

        gatt.enableControlNotifications()
        // Ob1 speakSlowly() — give CCCD write time before Control write.
        try {
            Thread.sleep(CCCD_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }

        syncTransmitterTime(shouldContinue)
        maybeSessionStartAfterTimeSync(shouldContinue)
        performBackfill(shouldContinue)

        var lastWriteMs = 0L
        writeEgvRequest(preferShort = true)
        lastWriteMs = System.currentTimeMillis()
        var preferShort = true
        var consecutiveTimeouts = 0

        while (shouldContinue() && gatt.isConnected()) {
            val now = System.currentTimeMillis()
            if (now - lastWriteMs >= rewriteIntervalMs) {
                writeEgvRequest(preferShort = preferShort)
                lastWriteMs = now
            }
            val packet = gatt.awaitControlNotify(notifyTimeoutMs)
            if (!shouldContinue()) break
            if (packet == null) {
                consecutiveTimeouts++
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: Control notify timeout ${notifyTimeoutMs}ms " +
                        "n=$consecutiveTimeouts — re-request EGV",
                )
                // After a few short-form misses, try CRC form once (Ob1 length 3).
                if (consecutiveTimeouts >= 2) preferShort = false
                writeEgvRequest(preferShort = preferShort)
                lastWriteMs = System.currentTimeMillis()
                continue
            }
            consecutiveTimeouts = 0
            preferShort = true
            handleControlPacket(packet, shouldContinue)
        }
        Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: Control/EGV loop exit")
    }

    /**
     * Finite initial poll (useful for tests / one-shot). Prefer [run] after auth in production path.
     *
     * @return true if at least one Control EGV packet was seen or warm-up reported healthy.
     */
    fun runInitialPoll(maxRounds: Int = 6, stepTimeoutMs: Long = 20_000L): Boolean {
        if (!gatt.isConnected()) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: EGV GATT not connected")
            return false
        }
        gatt.enableControlNotifications()
        try {
            Thread.sleep(CCCD_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        syncTransmitterTime(shouldContinue = { true })
        maybeSessionStartAfterTimeSync(shouldContinue = { true })
        performBackfill(shouldContinue = { true })
        var gotPacket = false
        var deliveredGlucose = false
        for (round in 0 until maxRounds) {
            val preferShort = round % 2 == 0
            writeEgvRequest(preferShort = preferShort)
            val packet = gatt.awaitControlNotify(stepTimeoutMs) ?: continue
            gotPacket = true
            if (OnePlusSessionStartRx.parse(packet) != null) continue
            if (OnePlusSessionStopRx.parse(packet) != null) continue
            if (OnePlusTransmitterTimeRx.parse(packet) != null) continue
            val parsed = OnePlusGlucoseParser.parseControlPacket(packet) ?: continue
            val warmup = OnePlusCalibrationMapper.toWarmupState(
                state = parsed.calibration,
                sessionAgeSeconds = parsed.sessionAgeSeconds,
            )
            onWarmup(warmup)
            val sample = parsed.sample
            if (parsed.usable && sample != null) {
                onGlucose(sample)
                deliveredGlucose = true
                break
            }
            if (warmup.phase == OnePlusWarmupState.Phase.WARMING) return true
            if (warmup.phase == OnePlusWarmupState.Phase.FAILED) return false
        }
        return deliveredGlucose || gotPacket
    }

    /**
     * Ob1-style GET_TIME before SessionStart so dexTime is non-zero when possible.
     * ⚠️ ASYNC IMPACT: blocks bleExecutor on Control write + await.
     */
    private fun syncTransmitterTime(shouldContinue: () -> Boolean) {
        if (!gatt.isConnected() || !shouldContinue()) return
        try {
            gatt.writeControl(OnePlusTransmitterTimeTx.request())
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: wrote TransmitterTimeTx opcode=0x24",
            )
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_TIME_WRITE_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
            onError(msg, false)
            return
        }

        val packet = gatt.awaitControlNotify(TRANSMITTER_TIME_TIMEOUT_MS)
        if (packet == null) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: TransmitterTimeRx timeout — continuing without dexTime",
            )
            return
        }

        val rx = OnePlusTransmitterTimeRx.parse(packet)
        if (rx == null) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: TransmitterTime await got non-0x25; handling as Control",
            )
            handleControlPacket(packet, shouldContinue)
            return
        }
        applyTransmitterTime(rx)
    }

    private fun applyTransmitterTime(rx: OnePlusTransmitterTimeRx) {
        lastDexTimeSeconds = rx.currentTimeSeconds
        sessionAlreadyInProgress = rx.sessionInProgress()
        val age = rx.sessionAgeSeconds()
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: TransmitterTimeRx current=${rx.currentTimeSeconds} " +
                "sessionStart=${rx.sessionStartTimeSeconds} inProgress=${rx.sessionInProgress()} " +
                "ageSec=$age status=0x${rx.statusByte.toString(16)}",
        )
        if (rx.sessionInProgress()) {
            val startMs = rx.realSessionStartEpochMs()
            if (startMs != null) {
                val warmup = OnePlusWarmupClock.warmingFromStart(
                    startEpochMs = startMs,
                    nowMs = System.currentTimeMillis(),
                    message = "tx_time_session",
                )
                onWarmup(warmup)
            }
        }
    }

    /**
     * Safe attach / start policy (Dexcom recovery):
     * - Session already running → **attach only** (no SessionStop 0x28, no SessionStart).
     * - No session + [requestNewSensorStart] → SessionStart 0x26 only.
     *
     * Automatic Stop→Start was removed: stopping the transmitter session can make the
     * same sensor unrecoverable in the official Dexcom app.
     */
    private fun maybeSessionStartAfterTimeSync(shouldContinue: () -> Boolean) {
        if (sessionAlreadyInProgress) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: attach existing transmitter session — " +
                    "skip SessionStop/SessionStart (Dexcom-safe)",
            )
            sessionStartAttempted = true
            return
        }
        if (!requestNewSensorStart) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: no transmitter session and requestNewSensorStart=false — EGV only",
            )
            return
        }
        performSessionStart(reason = "requestNewSensorStart", shouldContinue = shouldContinue)
    }

    /**
     * @return true if SessionStopRx reported OK (or we got a clear stop acceptance)
     */
    private fun performSessionStop(shouldContinue: () -> Boolean): Boolean {
        if (!gatt.isConnected() || !shouldContinue()) return false
        val payload = OnePlusSessionStopTx.build(stopTimeDexSeconds = lastDexTimeSeconds)
        try {
            gatt.writeControl(payload)
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: wrote SessionStopTx opcode=0x28 " +
                    "dexTime=$lastDexTimeSeconds",
            )
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_SESSION_STOP_WRITE_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
            onError(msg, false)
            return false
        }

        val packet = gatt.awaitControlNotify(SESSION_STOP_TIMEOUT_MS)
        if (packet == null) {
            Log.w(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: SessionStopRx timeout")
            return false
        }

        val rx = OnePlusSessionStopRx.parse(packet)
        if (rx == null) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStop await got non-0x29; handling as Control",
            )
            handleControlPacket(packet, shouldContinue)
            return false
        }

        if (rx.transmitterTime != 0) {
            lastDexTimeSeconds = rx.transmitterTime
        }
        return if (rx.isOkay()) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStopRx OK stop=${rx.sessionStopTime} " +
                    "txTime=${rx.transmitterTime}",
            )
            true
        } else {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStopRx status=${rx.status} received=${rx.received}",
            )
            false
        }
    }

    private fun performSessionStart(reason: String, shouldContinue: () -> Boolean) {
        if (sessionStartAttempted) return
        sessionStartAttempted = true
        if (!gatt.isConnected() || !shouldContinue()) return

        val payload = OnePlusSessionStartTx.build(
            dexTimeSeconds = lastDexTimeSeconds,
            startTimeEpochMs = System.currentTimeMillis(),
        )
        try {
            gatt.writeControl(payload)
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: wrote SessionStartTx opcode=0x26 " +
                    "dexTime=$lastDexTimeSeconds reason=$reason",
            )
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_SESSION_START_WRITE_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
            onError(msg, false)
            return
        }

        val packet = gatt.awaitControlNotify(SESSION_START_TIMEOUT_MS)
        if (packet == null) {
            Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStartRx timeout — continuing EGV",
            )
            return
        }

        val rx = OnePlusSessionStartRx.parse(packet)
        if (rx == null) {
            // Could be an EGV that arrived first — process it and continue.
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStart await got non-0x27; handling as Control",
            )
            handleControlPacket(packet, shouldContinue)
            return
        }

        if (rx.transmitterTime != 0 && rx.transmitterTime != OnePlusSessionStartRx.INVALID_TIME) {
            lastDexTimeSeconds = rx.transmitterTime
        }

        when {
            rx.isOkay() -> Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStartRx OK info=${rx.info} msg=${rx.message()} " +
                    "sessionStart=${rx.sessionStartTime} txTime=${rx.transmitterTime}",
            )
            rx.isAlreadyStarted() -> Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStartRx already started — continuing EGV",
            )
            rx.isFubar() -> {
                Log.e(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.ERROR}: SessionStartRx fubar ${rx.message()}",
                )
                onError("ONEPLUS_SESSION_START_FUBAR: ${rx.message()}", false)
            }
            else -> Log.w(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: SessionStartRx status=${rx.status} info=${rx.info} " +
                    "msg=${rx.message()} — continuing EGV",
            )
        }
    }

    private fun performBackfill(shouldContinue: () -> Boolean) {
        if (!shouldContinue() || !gatt.isConnected()) return
        try {
            OnePlusBackfillSession(
                gatt = gatt,
                onGlucose = onGlucose,
                onError = onError,
            ).runOnce(
                currentDexTimeSeconds = lastDexTimeSeconds,
                shouldContinue = shouldContinue,
            )
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_BACKFILL_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
            onError(msg, false)
        }
    }

    private fun writeEgvRequest(preferShort: Boolean) {
        val payload = if (preferShort) {
            OnePlusEGlucoseTx.requestShort()
        } else {
            OnePlusEGlucoseTx.requestWithCrc()
        }
        try {
            gatt.writeControl(payload)
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: wrote EGlucoseTx short=$preferShort " +
                    "opcode=0x${(OnePlusEGlucoseTx.OPCODE.toInt() and 0xff).toString(16)}",
            )
        } catch (t: Throwable) {
            val msg = t.message ?: "ONEPLUS_CONTROL_WRITE_FAILED"
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: $msg", t)
            onError(msg, false)
        }
    }

    private fun handleControlPacket(packet: ByteArray, shouldContinue: () -> Boolean) {
        OnePlusTransmitterTimeRx.parse(packet)?.let { rx ->
            applyTransmitterTime(rx)
            return
        }

        OnePlusSessionStopRx.parse(packet)?.let { rx ->
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: late SessionStopRx status=${rx.status} ok=${rx.isOkay()}",
            )
            if (rx.transmitterTime != 0) lastDexTimeSeconds = rx.transmitterTime
            return
        }

        OnePlusSessionStartRx.parse(packet)?.let { rx ->
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: late SessionStartRx info=${rx.info} msg=${rx.message()}",
            )
            if (rx.transmitterTime != 0 && rx.transmitterTime != OnePlusSessionStartRx.INVALID_TIME) {
                lastDexTimeSeconds = rx.transmitterTime
            }
            return
        }

        val parsed = OnePlusGlucoseParser.parseControlPacket(packet)
        if (parsed == null) {
            val op = if (packet.isNotEmpty()) packet[0].toInt() and 0xff else -1
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: Control packet ignored opcode=0x${op.toString(16)} len=${packet.size}",
            )
            return
        }

        if (parsed.calibration == OnePlusCalibrationState.Stopped ||
            parsed.calibration == OnePlusCalibrationState.SensorStopped
        ) {
            if (!sessionStartAttempted && shouldContinue()) {
                performSessionStart(
                    reason = "calibration=${parsed.calibration.name}",
                    shouldContinue = shouldContinue,
                )
            }
        }

        val warmup = OnePlusCalibrationMapper.toWarmupState(
            state = parsed.calibration,
            nowMs = System.currentTimeMillis(),
            sessionAgeSeconds = parsed.sessionAgeSeconds,
        )
        onWarmup(warmup)

        val sample = parsed.sample
        if (parsed.usable && sample != null) {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.BG}: mgdl=${sample.mgdl} ts=${sample.timestampMs} " +
                    "cal=${parsed.calibration.name} op=0x${parsed.opcode.toString(16)}",
            )
            onGlucose(sample)
        } else {
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.WARMUP}: egv cal=${parsed.calibration.name} usable=false " +
                    "age=${parsed.ageSeconds} sessionAge=${parsed.sessionAgeSeconds}",
            )
        }
    }

    companion object {
        const val CCCD_SETTLE_MS: Long = 200L
        const val DEFAULT_NOTIFY_TIMEOUT_MS: Long = 10_000L
        const val DEFAULT_REWRITE_INTERVAL_MS: Long = 5 * 60_000L
        const val SESSION_START_TIMEOUT_MS: Long = 15_000L
        const val SESSION_STOP_TIMEOUT_MS: Long = 15_000L
        /** xDrip `SessionStopTxMessage.postExecuteGuardTime`. */
        const val SESSION_STOP_GUARD_MS: Long = 1_000L
        const val TRANSMITTER_TIME_TIMEOUT_MS: Long = 15_000L
    }
}
