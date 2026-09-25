package uk.co.ncartmell.mtg.app.pixels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.co.ncartmell.mtg.app.store.nowMillis

/**
 * Everything the UI knows about the physical die, and the only thing that talks to one.
 *
 * Holds Compose state rather than exposing the link directly, so the rest of the app never
 * sees a Bluetooth callback. Nothing here is required for the app to work: where there is
 * no die, or no Bluetooth, [supported] and [isConnected] are false and every screen
 * behaves exactly as it did before any of this existed.
 */
class PixelsController(
    private val link: PixelsLink = createPixelsLink(),
    private val clock: () -> Long = ::nowMillis,
) : PixelsListener {

    /** Whether to offer any of this at all. False hides every mention of dice. */
    val supported: Boolean get() = link.supported

    var status by mutableStateOf<PixelsStatus>(
        if (link.supported) PixelsStatus.Idle else PixelsStatus.Unsupported,
    )
        private set

    /** Dice found in the current scan, in the order they turned up. */
    var found by mutableStateOf<List<PixelsDevice>>(emptyList())
        private set

    val isConnected: Boolean get() = status is PixelsStatus.Connected

    /**
     * How many faces the connected die has, falling back to a d20.
     *
     * A die on firmware too old to say what it is still rolls perfectly well, and a d20 is
     * both the overwhelmingly common case and the one Magic actually asks for.
     */
    val dieFaces: Int get() = dieType.faces.takeIf { it > 1 } ?: 20

    /** Whatever is connected, or a d20 — the common case and the one Magic asks for. */
    private val dieType: PixelsDieType
        get() = (status as? PixelsStatus.Connected)
            ?.dieType
            ?.takeIf { it != PixelsDieType.UNKNOWN }
            ?: PixelsDieType.D20

    /** Where a face from the die goes. Set by the app state, which decides what it means. */
    var onFace: ((Int) -> Unit)? = null

    /** Called when a connected die goes away, so anything half-finished can be dropped. */
    var onConnectionLost: (() -> Unit)? = null

    /** When the last roll was accepted, so one throw cannot be counted twice. */
    private var lastFaceAt = 0L

    init {
        link.listen(this)
    }

    // --- to the die --------------------------------------------------------------------

    fun scan() {
        if (!supported) return
        found = emptyList()
        link.scan()
    }

    fun stopScan() = link.stopScan()

    fun connect(device: PixelsDevice) {
        link.stopScan()
        link.connect(device.id)
    }

    fun disconnect() = link.disconnect()

    /**
     * Blinks the die in a panel's colour, which arrives as `0xAARRGGBB` and goes out as
     * `0xRRGGBB` — the die has no use for an alpha channel.
     */
    fun blink(argb: Int, count: Int = 1, durationMs: Int = 700) {
        if (isConnected) link.blink(argb and 0xFFFFFF, count, durationMs)
    }

    // --- from the die ------------------------------------------------------------------

    override fun onStatus(status: PixelsStatus) {
        val wasConnected = isConnected
        this.status = status
        if (wasConnected && status !is PixelsStatus.Connected) onConnectionLost?.invoke()
    }

    override fun onFound(device: PixelsDevice) {
        // Scans report the same die repeatedly while it is advertising.
        if (found.none { it.id == device.id }) found = found + device
    }

    override fun onRolled(faceIndex: Int) {
        // One throw can report itself more than once as the die settles. Two numbers a
        // fraction of a second apart are one roll; during a roll-off, counting them twice
        // would quietly hand the second one to whoever was due to roll next.
        val at = clock()
        if (at - lastFaceAt < SETTLE_MS) return
        lastFaceAt = at
        onFace?.invoke(dieType.faceValue(faceIndex))
    }

    private companion object {
        /** Comfortably longer than a die takes to settle, far shorter than a pass round. */
        const val SETTLE_MS = 900L
    }
}
