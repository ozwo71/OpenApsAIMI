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
 * Pump loop mirrors Ob1 `Ob1G5StateMachine.doNext` + Juggluco reconnect short-auth:
 * - Install guide certs + optional saved shared key (persistence channel 2)
 * - When bonded + saved key, libkeks skips Round1–3 (`aNext` → RequestAuth)
 * - Bond via [OnePlusGattClient.awaitBondComplete] (CCCD teardown while BONDING)
 * - Success only when Ob1 would enter GET_DATA (`aNext` length==1)
 *
 * Juggluco recovery (ChallengeReply): if AuthStatus `authenticated != 1` on the short-auth
 * path, wipe the shared key and fail with [AuthResult.invalidateSharedKey] so the session
 * reconnects into full J-PAKE — never stall in KEKS `Unknown` waiting for more notifies.
 *
 * ⚠️ ASYNC IMPACT: blocks caller (bleExecutor) on [OnePlusGattClient.awaitKeksNotify] and
 * [OnePlusGattClient.awaitBondComplete]. Do not call from main.
 */
class OnePlusSessionAuthKeks(
    private val gatt: OnePlusGattClient,
    private val stepTimeoutMs: Long = 15_000L,
    /** Many 20-byte ExtraData chunks per 160-byte round (×3) plus Auth challenge / AuthStatus. */
    private val maxSteps: Int = 96,
    private val bondWaitMs: Long = 45_000L,
) : OnePlusSessionAuth {

    override fun authenticate(pairingCode: String, savedSharedKey: ByteArray?): AuthResult {
        if (!gatt.isConnected()) {
            return AuthResult(ok = false, message = "ONEPLUS_AUTH: GATT not connected")
        }
        val plugin = Plugin.getInstance(pairingCode)
        // Ob1: Pref keks_p1..p3 → setPersistence(8..10) before handshake (xDrip Auto Configure QR).
        OnePlusKeksGuideCerts.install(plugin)

        val preload = savedSharedKey?.takeIf { it.size == 16 }
        val usedShortAuthPreload = preload != null
        if (preload != null) {
            plugin.setPersistence(2, preload.copyOf())
            Log.i(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.SESSION}: KEKS saved shared key preloaded " +
                    "bonded=${gatt.isBonded()} (short-auth path if RoundStart)",
            )
        }

        plugin.amConnected()
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: KEKS amConnected bonded=${gatt.isBonded()} " +
                "preload=${usedShortAuthPreload}",
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
                evaluateAuthStatus(notify.payload, usedShortAuthPreload)?.let { return it }
            }

            val shouldEmit = try {
                when (notify.source) {
                    OnePlusKeksNotifySource.AUTHENTICATION -> plugin.receivedResponse(notify.payload)
                    OnePlusKeksNotifySource.EXTRA_DATA -> plugin.receivedData(notify.payload)
                }
            } catch (se: SecurityException) {
                return AuthResult(ok = false, message = "ONEPLUS_AUTH: ${se.message}")
            } catch (ipe: InvalidParameterException) {
                return AuthResult(
                    ok = false,
                    message = "ONEPLUS_AUTH: ${ipe.message}",
                    invalidateSharedKey = ipe.message?.contains("Missing QR", ignoreCase = true) == true,
                )
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
     * Juggluco ChallengeReply: `auth != 1` on short-auth → wipe key and force full J-PAKE next.
     * libkeks only treats authenticated==1 as success; auth=2 + sensor bonded stalls in Unknown.
     */
    private fun evaluateAuthStatus(
        payload: ByteArray,
        usedShortAuthPreload: Boolean,
    ): AuthResult? {
        val status = OnePlusAuthStatusRx.parse(payload) ?: return null
        Log.i(
            OnePlusLogMarkers.TAG,
            "${OnePlusLogMarkers.SESSION}: AuthStatus authenticated=${status.authenticated} " +
                "bonded=${status.bonded} localBonded=${gatt.isBonded()} preload=$usedShortAuthPreload",
        )

        if (status.needsKeyRefresh) {
            return AuthResult(
                ok = false,
                message = "ONEPLUS_AUTH: KEKS bond failure / key refresh required (bond=3)",
                invalidateSharedKey = true,
            )
        }

        if (!status.isAuthenticated) {
            // Short-auth with stale/wrong key (typical Samsung log: auth=2 bonded=1).
            // Also fail fast on full-pair AuthStatus ≠1 rather than waiting for notify timeout.
            val viaShort = usedShortAuthPreload
            Log.e(
                OnePlusLogMarkers.TAG,
                "${OnePlusLogMarkers.ERROR}: AuthStatus rejected authenticated=${status.authenticated} " +
                    "(need ${OnePlusAuthStatusRx.AUTHENTICATED_OK}) " +
                    if (viaShort) "— Juggluco-style short-auth key refresh"
                    else "— auth failed",
            )
            return AuthResult(
                ok = false,
                message = if (viaShort) {
                    "ONEPLUS_AUTH: KEKS short-auth rejected auth=${status.authenticated} — key refresh required"
                } else {
                    "ONEPLUS_AUTH: KEKS AuthStatus rejected auth=${status.authenticated}"
                },
                invalidateSharedKey = true,
            )
        }

        // authenticated==1 but sensor not bonded — request Android bond (Ob1 / Juggluco gap).
        if (!status.sensorReportsBonded && !gatt.isBonded()) {
            requestAndroidBond()
        }
        return null
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
                    AuthResult(ok = true, message = "keks_ok", sharedKey = key!!.copyOf())
                }
            }
            3 -> {
                AuthResult(
                    ok = false,
                    message = "ONEPLUS_AUTH: KEKS bond failure / key refresh required",
                    invalidateSharedKey = true,
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

    private fun requestAndroidBond(): Boolean {
        if (gatt.isBonded()) return true
        return try {
            if (!gatt.createBond()) {
                Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: createBond returned false")
                return false
            }
            // Juggluco: BroadcastReceiver + CCCD teardown/restore (not a bare isBonded poll).
            gatt.awaitBondComplete(bondWaitMs)
        } catch (t: Throwable) {
            Log.e(OnePlusLogMarkers.TAG, "${OnePlusLogMarkers.ERROR}: createBond ${t.message}", t)
            false
        }
    }
}
