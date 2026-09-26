package uk.co.ncartmell.mtg.app.pixels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Set from the Activity before the UI is created, the way storage is.
 *
 * Nullable rather than `lateinit`, unlike storage: an app with nowhere to save is broken,
 * whereas an app with no Bluetooth is simply one without dice, and should say so rather
 * than fall over.
 */
object AndroidPixels {
    var context: Context? = null

    /** Asks for the Bluetooth permissions and reports the answer on the main thread. */
    var requestPermissions: ((onResult: (Boolean) -> Unit) -> Unit)? = null

    /**
     * What the app needs at runtime to find and talk to a die.
     *
     * Android 12 replaced "scanning is a location capability" with permissions that say
     * what they mean. Below that a BLE scan genuinely does need location, however little
     * sense that makes for a die sitting on the table.
     */
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}

private val SERVICE = UUID.fromString(PixelsProtocol.SERVICE_UUID)
private val NOTIFY = UUID.fromString(PixelsProtocol.NOTIFY_UUID)
private val WRITE = UUID.fromString(PixelsProtocol.WRITE_UUID)

/** The standard descriptor that actually switches notifications on. */
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** A scan left running is a flat battery; long enough to find a die on the table. */
private const val SCAN_MS = 20_000L

/**
 * Talks to a die through the platform's own Bluetooth API.
 *
 * Permissions are checked before every entry point rather than assumed, which is why
 * lint's complaint is suppressed once at the class rather than argued with call by call.
 *
 * Every callback from Android arrives on a binder thread and does nothing but hand over to
 * the main thread, so all the state below is read and written from one thread only.
 */
@SuppressLint("MissingPermission")
private class AndroidPixelsLink(private val context: Context) : PixelsLink {

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var listener: PixelsListener? = null
    private var status: PixelsStatus = PixelsStatus.Idle
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    /** Names as advertised, since reading one back off a device needs a permission. */
    private val names = mutableMapOf<String, String>()

    private val stopScanLater = Runnable { stopScan() }

    /**
     * Messages waiting to go out, because a GATT connection carries one at a time.
     *
     * Android does not queue for you: a second write issued before the first reports back
     * is simply refused, and the failure is a boolean nobody checks. Asking the die what
     * it is and how full it is — two messages, one after the other, the moment it
     * connects — is exactly the case that loses one.
     */
    private val outbox = ArrayDeque<ByteArray>()
    private var writing = false

    override val supported: Boolean =
        manager != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    override fun listen(listener: PixelsListener?) {
        this.listener = listener
        listener?.onStatus(if (supported) status else PixelsStatus.Unsupported)
    }

    // --- scanning ----------------------------------------------------------------------

    override fun scan() {
        if (!supported || scanning) return
        withPermission {
            val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            if (scanner == null) {
                publish(PixelsStatus.Unavailable("Bluetooth is switched off"))
                return@withPermission
            }
            scanning = true
            publish(PixelsStatus.Scanning)
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            main.postDelayed(stopScanLater, SCAN_MS)
        }
    }

    override fun stopScan() {
        main.removeCallbacks(stopScanLater)
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (status is PixelsStatus.Scanning) publish(PixelsStatus.Idle)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device?.address ?: return
            // The advertised name rather than the device's: reading the latter needs
            // BLUETOOTH_CONNECT on Android 12 and up, which looking at a scan result does
            // not grant.
            val name = result.scanRecord?.deviceName ?: return
            main.post {
                names[address] = name
                listener?.onFound(PixelsDevice(address, name))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                publish(PixelsStatus.Failed("Could not scan for dice (error $errorCode)"))
            }
        }
    }

    // --- connecting --------------------------------------------------------------------

    override fun connect(id: String) {
        if (!supported) return
        withPermission {
            val device = runCatching { adapter?.getRemoteDevice(id) }.getOrNull()
            if (device == null) {
                publish(PixelsStatus.Failed("That die is no longer there"))
                return@withPermission
            }
            closeGatt()
            publish(PixelsStatus.Connecting(names[id] ?: DEFAULT_NAME))
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    override fun disconnect() {
        val had = status is PixelsStatus.Connected || status is PixelsStatus.Connecting
        closeGatt()
        if (had) publish(PixelsStatus.Idle)
    }

    /** Hangs up without announcing it, for the callers that publish their own status. */
    private fun closeGatt() {
        outbox.clear()
        writing = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            main.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        g.close()
                        if (gatt === g) gatt = null
                        publish(PixelsStatus.Idle)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            main.post {
                val notify = g.getService(SERVICE)?.getCharacteristic(NOTIFY)
                val cccd = notify?.getDescriptor(CCCD)
                if (notify == null || cccd == null) {
                    publish(PixelsStatus.Failed("That does not look like a Pixels die"))
                    g.disconnect()
                    return@post
                }
                g.setCharacteristicNotification(notify, true)
                enableNotifications(g, cccd)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int,
        ) {
            main.post {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    publish(PixelsStatus.Failed("The die would not report its rolls"))
                    g.disconnect()
                    return@post
                }
                publish(PixelsStatus.Connected(names[g.device.address] ?: DEFAULT_NAME))
                // Asked once, so the app can say what it is holding and how full it is.
                write(PixelsProtocol.whoAreYou)
                write(PixelsProtocol.requestBattery)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            main.post {
                writing = false
                drain()
            }
        }

        // The three-argument form arrived in API 33, and its default implementation calls
        // this one, so overriding the older signature covers every version the app runs on.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val bytes = characteristic.value ?: return
            main.post { receive(bytes) }
        }
    }

    // --- messages ----------------------------------------------------------------------

    override fun blink(rgb: Int, count: Int, durationMs: Int) {
        if (status !is PixelsStatus.Connected) return
        write(PixelsProtocol.blink(rgb, count, durationMs))
    }

    private fun receive(bytes: ByteArray) {
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

    /**
     * Telling Android is not telling the die: writing this descriptor is what actually
     * turns notifications on, and without it no roll ever arrives.
     */
    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, cccd: BluetoothGattDescriptor) {
        val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, on)
        } else {
            cccd.value = on
            g.writeDescriptor(cccd)
        }
    }

    /** Queues a message. Sent straight away when nothing else is in flight. */
    private fun write(bytes: ByteArray) {
        outbox += bytes
        drain()
    }

    private fun drain() {
        if (writing) return
        val g = gatt ?: run {
            outbox.clear()
            return
        }
        val characteristic = g.getService(SERVICE)?.getCharacteristic(WRITE) ?: return
        val bytes = outbox.removeFirstOrNull() ?: return
        writing = send(g, characteristic, bytes)
        // Nothing will report back for a write that was never accepted, so carry on
        // rather than leaving everything behind it stuck.
        if (!writing) drain()
    }

    @Suppress("DEPRECATION")
    private fun send(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = type
            characteristic.value = bytes
            g.writeCharacteristic(characteristic)
        }
    }

    // --- plumbing ----------------------------------------------------------------------

    private fun publish(next: PixelsStatus) {
        status = next
        listener?.onStatus(next)
    }

    private fun permitted() = AndroidPixels.permissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Runs [block] once the permissions are in hand, or explains why they are not. */
    private fun withPermission(block: () -> Unit) {
        if (permitted()) {
            block()
            return
        }
        val request = AndroidPixels.requestPermissions
        if (request == null) {
            publish(PixelsStatus.Unavailable("Bluetooth permission has not been granted"))
            return
        }
        request { granted ->
            if (granted) block()
            else publish(PixelsStatus.Unavailable("Bluetooth permission was refused"))
        }
    }

    private companion object {
        const val DEFAULT_NAME = "Pixels die"
    }
}

actual fun createPixelsLink(): PixelsLink =
    AndroidPixels.context?.let { AndroidPixelsLink(it) } ?: UnsupportedPixelsLink()
