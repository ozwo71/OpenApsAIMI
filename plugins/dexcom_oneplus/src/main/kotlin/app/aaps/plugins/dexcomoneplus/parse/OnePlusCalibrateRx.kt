package app.aaps.plugins.dexcomoneplus.parse

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control Calibrate response (opcode 0x35) — what the sensor did with the fingerstick.
 *
 * Layout (5 bytes incl. CRC): opcode | info | result | crc16
 *
 * The answer matters as much as the send: a refusal here is the sensor saying it is warming up, has
 * stopped, or does not trust the value. Nothing else in our driver can see that, so a refusal must
 * always reach the user instead of being swallowed.
 *
 * Provenance: xDrip `CalibrateRxMessage` (GPL-3.0).
 */
data class OnePlusCalibrateRx(
    val info: Int,
    val result: Int,
    val valid: Boolean,
) {

    /** The sensor took the value. [SECOND_NEEDED] and [DUPLICATE] are accepted outcomes too. */
    fun accepted(): Boolean = valid && (result == OK || result == SECOND_NEEDED || result == DUPLICATE)

    /** The sensor took it and asks for a second fingerstick before it fully trusts itself again. */
    fun wantsSecondCalibration(): Boolean = valid && result == SECOND_NEEDED

    fun message(): String = when {
        !valid                -> "Unable to decode"
        result == OK          -> "OK"
        result == 0x01        -> "Code 1"
        result == SECOND_NEEDED -> "Second calibration needed"
        result == REJECTED    -> "Rejected"
        result == STOPPED     -> "Sensor stopped"
        result == DUPLICATE   -> "Duplicate"
        result == NOT_READY   -> "Not ready to calibrate"
        else                  -> "Unknown code: $result"
    }

    companion object {

        const val OPCODE: Byte = 0x35
        const val PACKET_LENGTH: Int = 5

        const val OK = 0x00
        const val SECOND_NEEDED = 0x06
        const val REJECTED = 0x08
        const val STOPPED = 0x0B
        const val DUPLICATE = 0x0D
        const val NOT_READY = 0x0E

        fun parse(packet: ByteArray): OnePlusCalibrateRx? {
            if (packet.size != PACKET_LENGTH) return null
            if (packet[0] != OPCODE) return null
            if (!OnePlusFastCrc16.check(packet)) return null
            val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            data.get() // opcode
            val info = data.get().toInt() and 0xff
            val result = data.get().toInt() and 0xff
            return OnePlusCalibrateRx(info = info, result = result, valid = true)
        }
    }
}
