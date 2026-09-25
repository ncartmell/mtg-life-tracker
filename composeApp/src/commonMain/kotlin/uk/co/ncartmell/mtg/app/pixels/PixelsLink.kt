package uk.co.ncartmell.mtg.app.pixels

/** A die seen while scanning. [id] is whatever the platform needs to connect to it again. */
data class PixelsDevice(val id: String, val name: String)

/** How far the link has got, and the only thing the UI needs in order to describe it. */
sealed interface PixelsStatus {
    /** No Bluetooth on this platform at all, so the app never mentions dice. */
    data object Unsupported : PixelsStatus

    /** Supported, but switched off or not permitted. [reason] is shown to the player. */
    data class Unavailable(val reason: String) : PixelsStatus

    data object Idle : PixelsStatus

    data object Scanning : PixelsStatus

    data class Connecting(val name: String) : PixelsStatus

    data class Connected(
        val name: String,
        val dieType: PixelsDieType = PixelsDieType.UNKNOWN,
        val batteryPercent: Int? = null,
    ) : PixelsStatus

    data class Failed(val reason: String) : PixelsStatus
}

/**
 * What the app wants to hear from a die.
 *
 * Every implementation is responsible for calling these on the main thread. Bluetooth
 * callbacks land on a binder thread on Android and on whichever queue CoreBluetooth was
 * given on iOS, and pushing that detail up into shared code would mean every caller
 * wondering about it.
 */
interface PixelsListener {
    fun onStatus(status: PixelsStatus)

    /** A die found while scanning. May be called more than once for the same die. */
    fun onFound(device: PixelsDevice)

    /**
     * The connected die came to rest after a roll.
     *
     * The raw zero-based index, not a number: which number a face shows depends on which
     * die it is, and the link deliberately does not decide that.
     */
    fun onRolled(faceIndex: Int)
}

/**
 * One physical die, as much of it as a life tracker needs.
 *
 * Kept as small as [uk.co.ncartmell.mtg.app.store.Storage] is, and for the same reason:
 * each platform then implements something short against the API it already has, and
 * nothing above this layer knows which platform it is on. All the protocol lives in
 * [PixelsProtocol], in common code, so the two platform halves only carry bytes.
 *
 * Nothing in the app depends on a die being present. Every implementation of this may
 * report [PixelsStatus.Unsupported] and refuse everything, and the app is unchanged.
 */
interface PixelsLink {
    /** False where there is no Bluetooth to speak of, which hides the feature entirely. */
    val supported: Boolean

    fun listen(listener: PixelsListener?)

    /** Starts looking for dice. Stops on its own after a while rather than hunting forever. */
    fun scan()

    fun stopScan()

    fun connect(id: String)

    fun disconnect()

    /** Blinks the die in an `0xRRGGBB` colour. Does nothing when no die is connected. */
    fun blink(rgb: Int, count: Int = 1, durationMs: Int = 700)
}

/** Provided per platform. */
expect fun createPixelsLink(): PixelsLink

/** Where a platform has no Bluetooth, so the app can be built without one. */
internal class UnsupportedPixelsLink : PixelsLink {
    override val supported = false
    override fun listen(listener: PixelsListener?) = Unit
    override fun scan() = Unit
    override fun stopScan() = Unit
    override fun connect(id: String) = Unit
    override fun disconnect() = Unit
    override fun blink(rgb: Int, count: Int, durationMs: Int) = Unit
}
