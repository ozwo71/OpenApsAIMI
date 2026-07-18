package app.aaps.plugins.dexcomoneplus.session

/**
 * Session authentication / J-PAKE key exchange (A6.4).
 *
 * Production path: [OnePlusSessionAuthKeks] via `:plugins:libkeks` (GPL-3 xDrip pin).
 * **No proprietary Dexcom secrets or hard-coded session keys belong in this module.**
 */
interface OnePlusSessionAuth {
    /**
     * Runs auth after GATT connect. Returns success/failure without throwing for expected auth fails.
     */
    fun authenticate(pairingCode: String): AuthResult
}

data class AuthResult(
    val ok: Boolean,
    val message: String? = null,
)

/**
 * Always fails — used in unit tests without Bluetooth / libkeks.
 */
class OnePlusSessionAuthUnimplemented : OnePlusSessionAuth {
    override fun authenticate(pairingCode: String): AuthResult {
        val redactedLen = pairingCode.length
        return AuthResult(
            ok = false,
            message = "ONEPLUS_AUTH_UNIMPLEMENTED: J-PAKE not wired (codeLen=$redactedLen)",
        )
    }
}
