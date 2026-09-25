package uk.co.ncartmell.mtg.app.pixels

/**
 * What a Pixels die is, as far as a life tracker cares.
 *
 * The firmware sends an index into this list, so the order of these entries is the
 * protocol rather than a matter of taste — entries may be appended, never reordered.
 */
enum class PixelsDieType(val label: String, val faces: Int) {
    UNKNOWN("die", 0),
    D4("d4", 4),
    D6("d6", 6),
    D8("d8", 8),
    D10("d10", 10),
    D00("d00", 100),
    D12("d12", 12),
    D20("d20", 20),
    D6_PIPPED("d6", 6),
    D6_FUDGE("Fudge d6", 6);

    /**
     * The number printed on the face at [faceIndex], which the die counts from zero.
     *
     * One more than the index for every ordinary die, which is what the reference library
     * does for all of them. The exception is the percentile die, whose ten faces read 00
     * to 90 rather than 1 to 10 — a d00 reporting "7" where the face says 60 would be a
     * quiet lie, and it costs one line to tell the truth.
     *
     * A Fudge die is left alone: its faces are two each of minus, blank and plus, and
     * which index is which is not written down anywhere the app can check. It reports a
     * position, 1 to 6, rather than inventing a symbol.
     */
    fun faceValue(faceIndex: Int): Int = if (this == D00) faceIndex * 10 else faceIndex + 1

    companion object {
        fun ofCode(code: Int): PixelsDieType = entries.getOrElse(code) { UNKNOWN }
    }
}

/** The messages from a die this app has any use for. Everything else is dropped. */
sealed interface PixelsMessage {
    /**
     * The die came to rest after something that looked like a real roll.
     *
     * Carries the raw index rather than a number, because turning one into the other
     * depends on which die it is, and only the controller knows that.
     */
    data class Rolled(val faceIndex: Int) : PixelsMessage

    /** The die's answer to "who are you", which it sends once on connecting. */
    data class Identity(val dieType: PixelsDieType, val batteryPercent: Int?) : PixelsMessage
}

/**
 * The slice of the Pixels Bluetooth protocol this app speaks.
 *
 * A Pixels die is an ordinary BLE peripheral with a published, MIT-licensed protocol
 * (github.com/GameWithPixels). Everything here is byte-shuffling with no platform API in
 * sight, so it lives in common code and is tested the way the rules are. The two platform
 * halves under [PixelsLink] do nothing but carry these bytes to and from the die.
 *
 * Message layouts are little-endian and packed, matching the firmware structs.
 */
object PixelsProtocol {
    /** The die's GATT service, and the two characteristics on it. */
    const val SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NOTIFY_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val WRITE_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    private const val TYPE_WHO_ARE_YOU = 1
    private const val TYPE_I_AM_A_DIE = 2
    private const val TYPE_ROLL_STATE = 3
    private const val TYPE_BLINK = 29

    /**
     * The die is at rest after a movement big enough to count as a roll.
     *
     * The other states — being handled, mid-roll, or resting crooked — all carry a face
     * as well, and none of them is a number the table would read off the die.
     */
    private const val STATE_ROLLED = 1

    /** Asked once on connecting, so the app knows whether it is holding a d20. */
    val whoAreYou: ByteArray = byteArrayOf(TYPE_WHO_ARE_YOU.toByte())

    /** Null for anything this app has no use for, which is most of the protocol. */
    fun decode(bytes: ByteArray): PixelsMessage? = when (bytes.firstOrNull()?.toInt()?.and(0xFF)) {
        TYPE_ROLL_STATE -> {
            if (bytes.size < 3 || bytes[1].toInt() and 0xFF != STATE_ROLLED) null
            // Face indices start at zero; a d20 showing a twenty reports nineteen.
            else PixelsMessage.Rolled(bytes[2].toInt() and 0xFF)
        }

        TYPE_I_AM_A_DIE -> {
            if (bytes.size < 4) null
            else PixelsMessage.Identity(
                dieType = PixelsDieType.ofCode(bytes[3].toInt() and 0xFF),
                // Only on the current message layout. A die on older firmware still says
                // what it is; it simply does not say how full it is.
                batteryPercent = if (bytes.size >= 21) bytes[20].toInt() and 0xFF else null,
            )
        }

        else -> null
    }

    /**
     * Blinks the die [count] times over [durationMs] in an `0xRRGGBB` colour.
     *
     * [fade] is how soft the edges of each flash are, 0 being a hard on and off and 255
     * the most gradual the firmware offers.
     */
    fun blink(rgb: Int, count: Int, durationMs: Int, fade: Int = 200): ByteArray {
        // type, count, duration, colour, face mask, fade, loop — packed in that order.
        val out = ByteArray(14)
        out[0] = TYPE_BLINK.toByte()
        out[1] = count.coerceIn(1, 255).toByte()
        out.putShort(at = 2, value = durationMs.coerceIn(1, 0xFFFF))
        out.putInt(at = 4, value = rgb and 0xFFFFFF)
        // Every LED on the die rather than only the face that is up: the die is usually
        // lying in the middle of the table, where no one face is pointing at anybody.
        out.putInt(at = 8, value = ALL_LEDS)
        out[12] = fade.coerceIn(0, 255).toByte()
        out[13] = 0 // Not looping, or it would still be flashing at end of turn.
        return out
    }

    private const val ALL_LEDS = -1 // 0xffffffff

    private fun ByteArray.putShort(at: Int, value: Int) {
        this[at] = (value and 0xFF).toByte()
        this[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putInt(at: Int, value: Int) {
        for (i in 0 until 4) this[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}
