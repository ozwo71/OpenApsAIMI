package app.aaps.plugins.dexcomoneplus.session

import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClient
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusKeksNotifySource
import jamorham.keks.Plugin
import java.security.InvalidParameterException

/**
 * KEKS (libkeks) handshake driver over [OnePlusGattClient].
 *
 * Provenance: NightscoutFoundation/xDrip libkeks at A1 pin (GPL-3.0) via `:plugins:libkeks`.
 * Pump loop mirrors Ob1 `Ob1G5StateMachine.doNext`:
 * - initial `aNext` after `amConnected` (CCCD already enabled by GATT connect)
 * - Auth indication → `receivedResponse` → `aNext` only when true
 * - ExtraData notify → `receivedData` → `aNext` only when buffer full (true)
 * - Success only when Ob1 would enter GET_DATA (`aNext` length==1), **not** when the
 *   shared key first becomes computable (that is mid-handshake after Round3 / AuthRequest)
 *
 * ⚠️ ASYNC IMPACT: blocks caller (bleExecutor) on [OnePlusGattClient.awaitKeksNotify] and optional
 * bond wait. Do not call from main.
 */
class OnePlusSessionAuthKeks(
    private val gatt: OnePlusGattClient,
    private val stepTimeoutMs: Long = 15_000L,
    /** Many 20-byte ExtraData chunks per 160-byte round (×3) plus Auth challenge / AuthStatus. */
    private val maxSteps: Int = 96,
    private val bondWaitMs: Long = 45_000L,
) : OnePlusSessionAuth {

    override fun authenticate(pairingCode: String): AuthResult {
        if (!gatt.isConnected()) {
            return AuthResult(ok = false, message = "ONEPLUS_AUTH: GATT not connected")
        }
        val plugin = Plugin.getInstance(pairingCode)
        plugin.amConnected()
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: KEKS amConnected bonded=${gatt.isBonded()}",
        )

        // Ob1: first doNext after Auth CCCD setup (connect already enabled CCCDs).
        emitANext(plugin)?.let { return it }

        var sawSharedKey = false
        repeat(maxSteps) { step ->
            if (!gatt.isConnected()) {
                return AuthResult(ok = false, message = "ONEPLUS_AUTH: GATT disconnected")
            }

            val notify = gatt.awaitKeksNotify(stepTimeoutMs)
                ?: return AuthResult(ok = false, message = "ONEPLUS_AUTH: notify timeout step=$step")

            if (plugin.bondNow(notify.payload)) {
                val bondOk = requestAndroidBond()
                if (!bondOk) {
                    return AuthResult(
                        ok = false,
                        message = "ONEPLUS_AUTH: Android bond required / timed out — accept pairing prompt",
                    )
                }
            }

            if (notify.source == OnePlusKeksNotifySource.AUTHENTICATION) {
                maybeHandleAuthStatusBondGap(notify.payload)
            }

            val shouldEmit = try {
                when (notify.source) {
                    OnePlusKeksNotifySource.AUTHENTICATION -> plugin.receivedResponse(notify.payload)
                    OnePlusKeksNotifySource.EXTRA_DATA -> plugin.receivedData(notify.payload)
                }
            } catch (se: SecurityException) {
                return AuthResult(ok = false, message = "ONEPLUS_AUTH: ${se.message}")
            } catch (ipe: InvalidParameterException) {
                return AuthResult(ok = false, message = "ONEPLUS_AUTH: ${ipe.message}")
            } catch (npe: NullPointerException) {
                return AuthResult(
                    ok = false,
                    message = "ONEPLUS_AUTH: KEKS NPE ${npe.message} (incomplete round state)",
                )
            }

            if (shouldEmit) {
                Log.d(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: KEKS emit after ${notify.source} " +
                        "${notify.payload.size}b step=$step",
                )
                emitANext(plugin)?.let { return it }
            }

            // Log key availability but do NOT finish — Ob1 still needs AuthChallenge + AuthStatus.
            if (!sawSharedKey) {
                val shared = plugin.getPersistence(1)
                if (shared != null && shared.isNotEmpty()) {
                    sawSharedKey = true
                    Log.i(
                        OnePlusLogMarkers.TAG,
                        "${OnePlusLogMarkers.SESSION}: KEKS shared key available (${shared.size}b) " +
                            "step=$step — continuing Auth challenge / AuthStatus",
                    )
                }
            }
        }

        return AuthResult(
            ok = false,
            message = "ONEPLUS_AUTH: KEKS handshake incomplete after $maxSteps steps " +
                "(sharedKey=$sawSharedKey; need AuthStatus→GET_DATA)",
        )
    }

    /**
     * Ob1 `doNext` write side. Returns a terminal [AuthResult] when handshake ends;
     * null means continue waiting for notifies.
     */
    private fun emitANext(plugin: Plugin): AuthResult? {
        val next = try {
            plugin.aNext()
        } catch (npe: NullPointerException) {
            return AuthResult(
                ok = false,
                message = "ONEPLUS_AUTH: KEKS aNext NPE ${npe.message}",
            )
        }
        if (next == null) {
            Log.d(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: KEKS aNext null — wait for more notifies",
            )
            return null
        }
        return when (next.size) {
            1 -> {
                // Ob1: length==1 → auth complete (GET_DATA). Do not write GETDATA here —
                // Control/EGV path owns post-auth traffic.
                val key = plugin.getPersistence(1)
                val keyBytes = if (key != null && key.isNotEmpty()) key.size else 0
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: KEKS auth complete (aNext length=1, key=${keyBytes}b)",
                )
                if (keyBytes == 0) {
                    AuthResult(ok = false, message = "ONEPLUS_AUTH: GET_DATA without shared key")
                } else {
                    AuthResult(ok = true, message = "keks_ok")
                }
            }
            3 -> {
                AuthResult(
                    ok = false,
                    message = "ONEPLUS_AUTH: KEKS bond failure / key refresh required",
                )
            }
            else -> {
                // Ob1 doNext: ExtraData chunks first (NO_RESPONSE), then Auth (DEFAULT).
                // Includes AuthChallenge (0x04) and TIME_EXTENDED after AuthStatus paths.
                if (next.isNotEmpty()) {
                    gatt.writeExtraData(next.getOrNull(1))
                    gatt.writeAuthentication(next.getOrNull(0))
                }
                null
            }
        }
    }

    /**
     * AuthStatus (opcode 0x05): authenticated but not bonded — request bond and wait.
     * Unauthenticated + unbonded → clear failure (Ob1 BondFailure path).
     */
    private fun maybeHandleAuthStatusBondGap(notify: ByteArray) {
        if (notify.size < 3) return
        if ((notify[0].toInt() and 0xff) != AUTH_STATUS_OPCODE) return
        val authenticated = notify[1].toInt() and 0xff
        val bonded = notify[2].toInt() and 0xff
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: AuthStatus authenticated=$authenticated bonded=$bonded " +
                "localBonded=${gatt.isBonded()}",
        )
        if (authenticated == 1 && bonded != 1 && !gatt.isBonded()) {
            requestAndroidBond()
        }
    }

    private fun requestAndroidBond(): Boolean {
        if (gatt.isBonded()) return true
        return try {
            if (!gatt.createBond()) {
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: createBond returned false")
                return false
            }
            awaitBonded(bondWaitMs)
        } catch (t: Throwable) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: createBond ${t.message}", t)
            false
        }
    }

    private fun awaitBonded(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (gatt.isBonded()) {
                Log.i(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.SESSION}: Android bond complete")
                return true
            }
            if (!gatt.isConnected()) return false
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return gatt.isBonded()
    }

    companion object {
        private const val AUTH_STATUS_OPCODE: Int = 0x05
    }
}
