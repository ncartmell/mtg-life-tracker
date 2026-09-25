package uk.co.ncartmell.mtg.app.pixels

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The die's half of the conversation, checked without a die.
 *
 * Everything here is a layout the firmware decides and this app has to match, which makes
 * it exactly the sort of thing worth pinning down in a test: a wrong offset is invisible
 * until somebody is sitting at a table watching the wrong number come up.
 */
class PixelsProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // --- rolls -------------------------------------------------------------------------

    @Test
    fun `a settled roll carries the raw index, which starts at zero`() {
        assertEquals(PixelsMessage.Rolled(19), PixelsProtocol.decode(bytes(3, 1, 19)))
        assertEquals(PixelsMessage.Rolled(0), PixelsProtocol.decode(bytes(3, 1, 0)))
    }

    @Test
    fun `an ordinary die reads one more than its index`() {
        assertEquals(20, PixelsDieType.D20.faceValue(19))
        assertEquals(1, PixelsDieType.D20.faceValue(0))
        assertEquals(6, PixelsDieType.D6.faceValue(5))
        assertEquals(4, PixelsDieType.D4.faceValue(3))
        assertEquals(12, PixelsDieType.D12.faceValue(11))
        assertEquals(10, PixelsDieType.D10.faceValue(9))
    }

    @Test
    fun `a percentile die reads the tens actually printed on it`() {
        assertEquals(0, PixelsDieType.D00.faceValue(0), "the face reading 00")
        assertEquals(60, PixelsDieType.D00.faceValue(6))
        assertEquals(90, PixelsDieType.D00.faceValue(9))
    }

    @Test
    fun `a die that has not said what it is falls back to counting from one`() {
        assertEquals(14, PixelsDieType.UNKNOWN.faceValue(13))
    }

    @Test
    fun `a die still being handled or still rolling is not a number`() {
        assertNull(PixelsProtocol.decode(bytes(3, 2, 11)), "being handled")
        assertNull(PixelsProtocol.decode(bytes(3, 3, 11)), "mid-roll")
        assertNull(PixelsProtocol.decode(bytes(3, 4, 11)), "resting crooked")
        // Settled, but without a throw worth reporting — putting the die down is not a roll.
        assertNull(PixelsProtocol.decode(bytes(3, 5, 11)), "simply on a face")
        assertNull(PixelsProtocol.decode(bytes(3, 0, 11)), "unknown")
    }

    @Test
    fun `a truncated or unknown message is dropped rather than guessed at`() {
        assertNull(PixelsProtocol.decode(bytes()))
        assertNull(PixelsProtocol.decode(bytes(3)))
        assertNull(PixelsProtocol.decode(bytes(3, 1)))
        assertNull(PixelsProtocol.decode(bytes(4, 1, 2, 3)), "telemetry, which this app ignores")
    }

    // --- identity ----------------------------------------------------------------------

    /** The full message as the current firmware lays it out: 22 bytes, little-endian. */
    private fun iAmADie(dieType: Int, battery: Int) = ByteArray(22).also {
        it[0] = 2
        it[1] = 20 // LED count
        it[2] = 3 // colourway
        it[3] = dieType.toByte()
        it[20] = battery.toByte()
    }

    @Test
    fun `identifies a d20 and how full it is`() {
        val message = PixelsProtocol.decode(iAmADie(dieType = 7, battery = 86))
        assertEquals(PixelsMessage.Identity(PixelsDieType.D20, batteryPercent = 86), message)
        assertEquals(20, PixelsDieType.D20.faces)
    }

    @Test
    fun `an unfamiliar die type is unknown rather than a wrong guess`() {
        val message = assertIs<PixelsMessage.Identity>(
            PixelsProtocol.decode(iAmADie(dieType = 99, battery = 10)),
        )
        assertEquals(PixelsDieType.UNKNOWN, message.dieType)
    }

    @Test
    fun `older firmware still says what it is, without saying how full`() {
        // Enough for the die type and no more, which is what a shorter layout gives.
        val message = PixelsProtocol.decode(bytes(2, 20, 3, 7))
        assertEquals(PixelsMessage.Identity(PixelsDieType.D20, batteryPercent = null), message)
    }

    // --- blink -------------------------------------------------------------------------

    @Test
    fun `blink packs type, count, duration, colour, face mask, fade and loop`() {
        assertContentEquals(
            bytes(
                29, // blink
                3, // three flashes
                0x78, 0x05, // 1400ms, little-endian
                0x2F, 0x91, 0xB8, 0x00, // 0xB8912F, little-endian
                0xFF, 0xFF, 0xFF, 0xFF, // every LED
                200, // fade
                0, // not looping
            ),
            PixelsProtocol.blink(rgb = 0xB8912F, count = 3, durationMs = 1400, fade = 200),
        )
    }

    @Test
    fun `a panel colour arrives with an alpha channel the die has no use for`() {
        val withAlpha = PixelsProtocol.blink(rgb = 0xFFB8912F.toInt(), count = 1, durationMs = 700)
        val without = PixelsProtocol.blink(rgb = 0xB8912F, count = 1, durationMs = 700)
        assertContentEquals(without, withAlpha)
    }

    @Test
    fun `counts and durations are clamped to what the firmware can hold`() {
        val long = PixelsProtocol.blink(rgb = 0, count = 900, durationMs = 100_000)
        assertEquals(255.toByte(), long[1], "count is a single byte")
        assertContentEquals(bytes(0xFF, 0xFF), long.copyOfRange(2, 4), "duration is two")
    }

    @Test
    fun `who are you is a single byte`() {
        assertContentEquals(bytes(1), PixelsProtocol.whoAreYou)
    }
}
