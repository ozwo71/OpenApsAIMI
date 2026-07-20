package app.aaps.plugins.dexcomoneplus.identity

/**
 * Juggluco-aligned ADV name / candidate matching for ONE+ / G7 family.
 *
 * Juggluco `isG7`: local name starts with `DX` + (`CM`|`02`|`01`).
 * Once a device name is known from a successful session, prefer exact match.
 */
object OnePlusAdvCandidate {

    fun isG7FamilyName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim()
        if (n.length < 4) return false
        if (!n.startsWith("DX", ignoreCase = true)) return false
        val rest = n.substring(2, 4)
        return rest.equals("CM", ignoreCase = true) ||
            rest.equals("02", ignoreCase = true) ||
            rest.equals("01", ignoreCase = true)
    }

    /** Broader soft filter used during open scan (includes Dex* marketing names). */
    fun nameMatchesSoft(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim()
        return isG7FamilyName(n) ||
            n.startsWith("DXC", ignoreCase = true) ||
            n.startsWith("Dex", ignoreCase = true)
    }

    /**
     * Whether this ADV should be offered / preferred for [session].
     * - Exact stored ADV name wins (reconnect sticky).
     * - Else G7-family name, optionally preferring serial substring when known.
     */
    fun isCandidate(
        name: String?,
        address: String?,
        session: OnePlusStoredSession?,
    ): Boolean {
        val storedName = session?.lastDeviceName
        if (!storedName.isNullOrBlank() && name != null && name.equals(storedName, ignoreCase = true)) {
            return true
        }
        val storedMac = session?.lastMac
        if (!storedMac.isNullOrBlank() && address != null &&
            address.equals(storedMac, ignoreCase = true)
        ) {
            return true
        }
        if (!nameMatchesSoft(name)) return false
        val serial = session?.identity?.serial
        if (!serial.isNullOrBlank() && !name.isNullOrBlank()) {
            // Prefer ADV that embeds serial characters when present; do not hard-reject
            // when serial encoding in ADV is opaque (G7 often uses short DX02* names).
            if (name.contains(serial, ignoreCase = true)) return true
        }
        return isG7FamilyName(name) || nameMatchesSoft(name)
    }

    fun rankScore(
        name: String?,
        address: String?,
        rssi: Int,
        session: OnePlusStoredSession?,
    ): Int {
        var score = rssi
        if (!session?.lastDeviceName.isNullOrBlank() &&
            name.equals(session?.lastDeviceName, ignoreCase = true)
        ) {
            score += 10_000
        }
        if (!session?.lastMac.isNullOrBlank() &&
            address.equals(session?.lastMac, ignoreCase = true)
        ) {
            score += 5_000
        }
        val serial = session?.identity?.serial
        if (!serial.isNullOrBlank() && name?.contains(serial, ignoreCase = true) == true) {
            score += 1_000
        }
        if (isG7FamilyName(name)) score += 100
        return score
    }
}
