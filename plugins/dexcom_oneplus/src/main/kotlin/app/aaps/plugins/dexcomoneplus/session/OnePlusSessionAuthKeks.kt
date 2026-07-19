package app.aaps.plugins.dexcomoneplus.session

import android.util.Log
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClient
import jamorham.keks.Plugin
import java.security.InvalidParameterException

/**
 * KEKS (libkeks) handshake driver over [OnePlusGattClient].
 *
 * Provenance: NightscoutFoundation/xDrip libkeks at A1 pin (GPL-3.0) via `:plugins:libkeks`.
 * Pump loop mirrors Ob1’s IPluginDA aNext / receivedResponse / receivedData / bondNow pattern.
 *
 * ⚠️ ASYNC IMPACT: blocks caller (bleExecutor) on [OnePlusGattClient.awaitNotify] and optional
 * bond wait. Do not call from main.
 */
class OnePlusSessionAuthKeks(
    private val gatt: OnePlusGattClient,
    private val stepTimeoutMs: Long = 15_000L,
    private val maxSteps: Int = 48,
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

        repeat(maxSteps) { step ->
            val shared = plugin.getPersistence(1)
            if (shared != null && shared.isNotEmpty()) {
                Log.i(
                    OnePlusLogMarkers.TAG,
                    "${OnePlusLogMarkers.SESSION}: KEKS shared key ready (${shared.size}b) step=$step",
                )
                return AuthResult(ok = true, message = "keks_ok")
            }

            val next = plugin.aNext()
            if (next == null) {
                return AuthResult(ok = false, message = "ONEPLUS_AUTH: KEKS aNext null (state stalled)")
            }

            // Ob1 doNext: ExtraData chunks first (NO_RESPONSE), then Auth (DEFAULT).
            // Writing Auth before ExtraData (or without GATT serialization) yields Android
            // status 201 ERROR_GATT_WRITE_REQUEST_BUSY on API 33+.
            if (next.isNotEmpty()) {
                gatt.writeExtraData(next.getOrNull(1))
                gatt.writeAuthentication(next.getOrNull(0))
            }

            val notify = gatt.awaitNotify(stepTimeoutMs)
                ?: return AuthResult(ok = false, message = "ONEPLUS_AUTH: notify timeout step=$step")

            // Ob1: TIME_EXTENDED* on Auth stream → Android createBond.
            if (plugin.bondNow(notify)) {
                val bondOk = requestAndroidBond()
                if (!bondOk) {
                    return AuthResult(
                        ok = false,
                        message = "ONEPLUS_AUTH: Android bond required / timed out — accept pairing prompt",
                    )
                }
            }

            maybeHandleAuthStatusBondGap(notify)

            val handledResponse = try {
                plugin.receivedResponse(notify)
            } catch (se: SecurityException) {
                return AuthResult(ok = false, message = "ONEPLUS_AUTH: ${se.message}")
            } catch (ipe: InvalidParameterException) {
                return AuthResult(ok = false, message = "ONEPLUS_AUTH: ${ipe.message}")
            }
            if (!handledResponse) {
                plugin.receivedData(notify)
            }
        }

        val finalKey = plugin.getPersistence(1)
        return if (finalKey != null && finalKey.isNotEmpty()) {
            AuthResult(ok = true, message = "keks_ok")
        } else {
            AuthResult(ok = false, message = "ONEPLUS_AUTH: KEKS did not produce shared key in $maxSteps steps")
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
