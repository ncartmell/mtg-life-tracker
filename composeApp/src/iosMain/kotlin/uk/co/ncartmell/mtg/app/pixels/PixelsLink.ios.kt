package uk.co.ncartmell.mtg.app.pixels

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSTimer
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

private val SERVICE = CBUUID.UUIDWithString(PixelsProtocol.SERVICE_UUID)
private val NOTIFY = CBUUID.UUIDWithString(PixelsProtocol.NOTIFY_UUID)
private val WRITE = CBUUID.UUIDWithString(PixelsProtocol.WRITE_UUID)

/** A scan left running is a flat battery; long enough to find a die on the table. */
private const val SCAN_SECONDS = 20.0

private const val DEFAULT_NAME = "Pixels die"

/**
 * Talks to a die through CoreBluetooth, which Kotlin/Native already has bindings for.
 *
 * The central manager is built on first use rather than at launch, and deliberately: iOS
 * puts up the "would like to use Bluetooth" prompt the moment one exists, and a life
 * tracker has no business asking about Bluetooth before anybody has asked for a die.
 *
 * CoreBluetooth is given the main queue, so every callback lands on the main thread and
 * all the state below is touched from one thread.
 */
private class IosPixelsLink : PixelsLink {

    // Kotlin cannot mix Kotlin and Objective-C supertypes in one class, so the delegate is
    // an object of its own that does nothing but forward to this one.
    private val delegate = PixelsDelegate(this)

    private var central: CBCentralManager? = null
    private var listener: PixelsListener? = null
    private var status: PixelsStatus = PixelsStatus.Idle

    /** Peripherals seen while scanning, so connecting does not have to go and find them. */
    private val seen = mutableMapOf<String, CBPeripheral>()

    private var connected: CBPeripheral? = null
    private var writeTo: CBCharacteristic? = null
    private var scanTimer: NSTimer? = null

    /** Set when a scan was asked for before the radio was ready to do one. */
    private var scanWanted = false

    override val supported = true

    override fun listen(listener: PixelsListener?) {
        this.listener = listener
        listener?.onStatus(status)
    }

    // --- scanning ----------------------------------------------------------------------

    override fun scan() {
        scanWanted = true
        val manager = central
            ?: CBCentralManager(delegate, dispatch_get_main_queue()).also { central = it }
        // Until the radio reports itself powered on there is nothing to scan with, so the
        // request is held and picked up again in onStateChanged.
        if (manager.state == CBManagerStatePoweredOn) beginScan(manager)
    }

    private fun beginScan(manager: CBCentralManager) {
        scanWanted = false
        seen.clear()
        publish(PixelsStatus.Scanning)
        manager.scanForPeripheralsWithServices(listOf(SERVICE), options = null)
        scanTimer?.invalidate()
        scanTimer = NSTimer.scheduledTimerWithTimeInterval(SCAN_SECONDS, repeats = false) {
            stopScan()
        }
    }

    override fun stopScan() {
        scanWanted = false
        scanTimer?.invalidate()
        scanTimer = null
        central?.stopScan()
        if (status is PixelsStatus.Scanning) publish(PixelsStatus.Idle)
    }

    // --- connecting --------------------------------------------------------------------

    override fun connect(id: String) {
        // Built here rather than only in scan(), so reconnecting to a remembered die does
        // not have to go looking for something it already knows the identity of.
        val manager = central
            ?: CBCentralManager(delegate, dispatch_get_main_queue()).also { central = it }
        val peripheral = seen[id] ?: manager.knownPeripheral(id)?.also { seen[id] = it }
        if (peripheral == null) {
            publish(PixelsStatus.Failed("That die is no longer there"))
            return
        }
        stopScan()
        publish(PixelsStatus.Connecting(peripheral.name ?: DEFAULT_NAME))
        peripheral.delegate = delegate
        connected = peripheral
        manager.connectPeripheral(peripheral, options = null)
    }

    override fun disconnect() {
        val peripheral = connected ?: return
        connected = null
        writeTo = null
        central?.cancelPeripheralConnection(peripheral)
        publish(PixelsStatus.Idle)
    }

    override fun blink(rgb: Int, count: Int, durationMs: Int) {
        if (status !is PixelsStatus.Connected) return
        write(PixelsProtocol.blink(rgb, count, durationMs))
    }

    private fun write(bytes: ByteArray) {
        val peripheral = connected ?: return
        val characteristic = writeTo ?: return
        peripheral.writeValue(
            bytes.toNSData(),
            forCharacteristic = characteristic,
            type = CBCharacteristicWriteWithResponse,
        )
    }

    // --- from the delegate -------------------------------------------------------------

    fun onStateChanged(manager: CBCentralManager) {
        when (manager.state) {
            CBManagerStatePoweredOn -> if (scanWanted) beginScan(manager)
            CBManagerStatePoweredOff ->
                publish(PixelsStatus.Unavailable("Bluetooth is switched off"))

            CBManagerStateUnauthorized ->
                publish(PixelsStatus.Unavailable("Bluetooth permission was refused"))

            CBManagerStateUnsupported ->
                publish(PixelsStatus.Unavailable("This device has no Bluetooth"))

            else -> Unit // Resetting or still starting up; the next update will say.
        }
    }

    fun onDiscovered(peripheral: CBPeripheral, advertisedName: String?) {
        val id = peripheral.identifier.UUIDString
        seen[id] = peripheral
        listener?.onFound(PixelsDevice(id, advertisedName ?: peripheral.name ?: DEFAULT_NAME))
    }

    fun onConnected(peripheral: CBPeripheral) {
        peripheral.discoverServices(listOf(SERVICE))
    }

    fun onConnectFailed(error: NSError?) {
        connected = null
        publish(PixelsStatus.Failed(error?.localizedDescription ?: "Could not reach that die"))
    }

    fun onDisconnected() {
        connected = null
        writeTo = null
        publish(PixelsStatus.Idle)
    }

    fun onServices(peripheral: CBPeripheral) {
        val service = peripheral.services
            ?.filterIsInstance<CBService>()
            ?.firstOrNull { it.UUID == SERVICE }
        if (service == null) {
            publish(PixelsStatus.Failed("That does not look like a Pixels die"))
            disconnect()
            return
        }
        peripheral.discoverCharacteristics(listOf(NOTIFY, WRITE), forService = service)
    }

    fun onCharacteristics(peripheral: CBPeripheral, service: CBService) {
        val characteristics = service.characteristics?.filterIsInstance<CBCharacteristic>().orEmpty()
        val notify = characteristics.firstOrNull { it.UUID == NOTIFY }
        writeTo = characteristics.firstOrNull { it.UUID == WRITE }
        if (notify == null) {
            publish(PixelsStatus.Failed("That does not look like a Pixels die"))
            disconnect()
            return
        }
        peripheral.setNotifyValue(true, forCharacteristic = notify)
        publish(PixelsStatus.Connected(peripheral.name ?: DEFAULT_NAME))
        // Asked once, so the app can say what it is holding and how full it is.
        write(PixelsProtocol.whoAreYou)
        write(PixelsProtocol.requestBattery)
    }

    fun onValue(bytes: ByteArray) {
        when (val message = PixelsProtocol.decode(bytes)) {
            is PixelsMessage.Rolled -> listener?.onRolled(message.faceIndex)
            is PixelsMessage.Identity -> (status as? PixelsStatus.Connected)?.let {
                publish(it.copy(dieType = message.dieType, batteryPercent = message.batteryPercent))
            }

            is PixelsMessage.Battery -> (status as? PixelsStatus.Connected)?.let {
                publish(it.copy(batteryPercent = message.percent))
            }

            null -> Unit
        }
    }

    private fun publish(next: PixelsStatus) {
        status = next
        listener?.onStatus(next)
    }
}

/** Nothing but a bridge: CoreBluetooth's delegate calls, forwarded to [IosPixelsLink]. */
private class PixelsDelegate(private val link: IosPixelsLink) :
    NSObject(),
    CBCentralManagerDelegateProtocol,
    CBPeripheralDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) =
        link.onStateChanged(central)

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) = link.onDiscovered(
        didDiscoverPeripheral,
        advertisementData[CBAdvertisementDataLocalNameKey] as? String,
    )

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) =
        link.onConnected(didConnectPeripheral)

    // Both of these selectors erase to the same Kotlin signature, which parameter names do
    // not tell apart, so the compiler has to be told they are genuinely different methods.
    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) = link.onConnectFailed(error)

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?,
    ) = link.onDisconnected()

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) =
        link.onServices(peripheral)

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?,
    ) = link.onCharacteristics(peripheral, didDiscoverCharacteristicsForService)

    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) {
        val data = didUpdateValueForCharacteristic.value ?: return
        link.onValue(data.toByteArray())
    }
}

/**
 * A peripheral iOS already knows about, looked up by the identifier we stored.
 *
 * CoreBluetooth hands out its own UUID per device per install, which is exactly what
 * this is for: the die does not have to be advertising, and no scan is needed.
 */
private fun CBCentralManager.knownPeripheral(id: String): CBPeripheral? {
    val uuid = NSUUID(uUIDString = id)
    return retrievePeripheralsWithIdentifiers(listOf(uuid))
        .filterIsInstance<CBPeripheral>()
        .firstOrNull()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

actual fun createPixelsLink(): PixelsLink = IosPixelsLink()
