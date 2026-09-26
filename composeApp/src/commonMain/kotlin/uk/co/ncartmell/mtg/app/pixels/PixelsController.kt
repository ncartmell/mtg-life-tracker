package uk.co.ncartmell.mtg.app.pixels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.co.ncartmell.mtg.app.store.DieMemory
import uk.co.ncartmell.mtg.app.store.DieRepository
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
    private val dice: DieRepository? = null,
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

    /** The die this device used last, offered back rather than hunted for again. */
    var remembered by mutableStateOf(dice?.load() ?: DieMemory())
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

    val batteryPercent: Int? get() = (status as? PixelsStatus.Connected)?.batteryPercent

    /** Low enough to be worth mentioning before a game rather than during one. */
    val batteryLow: Boolean get() = batteryPercent?.let { it <= LOW_BATTERY } == true

    /**
     * Whether the die lights up for turns and tokens as well as for rolls.
     *
     * Off is a perfectly reasonable preference — the die is a shared object in the middle
     * of the table, and a light that goes off every turn is either delightful or maddening
     * depending on the group. It is remembered either way.
     */
    var lightsTheTable: Boolean
        get() = remembered.lightsTheTable
        set(value) = rememberDie(remembered.copy(lightsTheTable = value))

    /** Where a face from the die goes. Set by the app state, which decides what it means. */
    var onFace: ((Int) -> Unit)? = null

    /** Called when a connected die goes away, so anything half-finished can be dropped. */
    var onConnectionLost: (() -> Unit)? = null

    /** When the last roll was accepted, so one throw cannot be counted twice. */
    private var lastFaceAt = 0L

    /** What we are connecting to, so it can be remembered once it answers. */
    private var pendingId: String? = null
    private var pendingName: String? = null

    /**
     * Set while reconnecting to a remembered die without being asked to.
     *
     * A die left in its bag is the ordinary case, and an error message about it every
     * time the app opens would be noise about something nobody requested.
     */
    private var quietly = false

    init {
        link.listen(this)
    }

    // --- to the die --------------------------------------------------------------------

    fun scan() {
        if (!supported) return
        found = emptyList()
        quietly = false
        link.scan()
    }

    fun stopScan() = link.stopScan()

    fun connect(device: PixelsDevice) {
        link.stopScan()
        quietly = false
        pendingId = device.id
        pendingName = device.name
        link.connect(device.id)
    }

    /**
     * Tries the die this device used last, saying nothing if it is not about.
     *
     * Called when a game starts rather than at launch: that is the moment somebody is
     * plausibly sitting down with the die, and it keeps the radio off the rest of the time.
     */
    fun reconnectRemembered() {
        val id = remembered.id
        if (!supported || id.isNullOrBlank() || isConnected) return
        if (status is PixelsStatus.Connecting || status is PixelsStatus.Scanning) return
        quietly = true
        pendingId = id
        pendingName = remembered.name
        link.connect(id)
    }

    fun disconnect() {
        quietly = false
        link.disconnect()
    }

    /** Stops offering the remembered die, for a die that has been given away. */
    fun forgetDie() {
        rememberDie(DieMemory(lightsTheTable = remembered.lightsTheTable))
    }

    /**
     * Blinks the die in a panel's colour, which arrives as `0xAARRGGBB` and goes out as
     * `0xRRGGBB` — the die has no use for an alpha channel.
     */
    fun blink(argb: Int, count: Int = 1, durationMs: Int = 700) {
        if (isConnected) link.blink(argb and 0xFFFFFF, count, durationMs)
    }

    /**
     * Blinks only if the table has asked for the die to follow the game.
     *
     * Rolls always light the die, because a roll is something the die itself did. Turns and
     * tokens are the app talking, which is the part somebody might not want.
     */
    fun blinkForTable(argb: Int, count: Int = 1, durationMs: Int = 700) {
        if (lightsTheTable) blink(argb, count, durationMs)
    }

    // --- from the die ------------------------------------------------------------------

    override fun onStatus(status: PixelsStatus) {
        val wasConnected = isConnected
        // A die that was not asked for and is not there is not news.
        val quiet = quietly && status is PixelsStatus.Failed
        this.status = if (quiet) PixelsStatus.Idle else status

        if (status is PixelsStatus.Connected) {
            quietly = false
            pendingId?.let { rememberDie(remembered.copy(id = it, name = status.name)) }
        }
        if (status !is PixelsStatus.Connecting) pendingId = null
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

    private fun rememberDie(memory: DieMemory) {
        remembered = memory
        dice?.save(memory)
    }

    private companion object {
        /** Comfortably longer than a die takes to settle, far shorter than a pass round. */
        const val SETTLE_MS = 900L

        /** Enough left for an evening, but worth saying so before one starts. */
        const val LOW_BATTERY = 20
    }
}
