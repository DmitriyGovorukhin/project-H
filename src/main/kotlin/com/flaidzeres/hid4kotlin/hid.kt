package com.flaidzeres.hid4kotlin

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList

abstract class MOD

object HID : MOD()
object HIDRAW : MOD()

interface HidDevice : AutoCloseable {
    fun read(bytes: ByteArray, timeOut: Int = 0): Int

    fun write(bytes: ByteArray): Int

    fun open(): Boolean

    fun open(block: HidDevice.() -> Unit)

    fun isOpen(): Boolean

    fun error(): String

    fun nonBlocking(mod: Boolean): Int

    fun id(): String

    fun serialNumber(): String

    fun product(): String

    fun manufacturer(): String

    fun vendorId(): Short

    fun productId(): Short

    fun releaseNumber(): Short

    fun usagePage(): Short

    fun usage(): Short

    fun interfaceNumber(): Int
}

interface HidDeviceManager : AutoCloseable {
    companion object {
        operator fun invoke(scanFreq: Long = 5000L, mod: MOD = HID): HidDeviceManager = DeviceManager(scanFreq, mod)
    }

    fun open(block: HidDeviceManager.() -> Unit)

    fun devices(): List<HidDevice>

    fun device(vendorId: Short, productId: Short): HidDevice

    fun device(id: String): HidDevice

    operator fun plusAssign(l: HidDeviceListener)

    operator fun minusAssign(l: HidDeviceListener)
}

interface HidDeviceListener {
    fun onConnect(d: HidDevice)

    fun onDisconnect(d: HidDevice)
}

class DeviceNotFound : Exception()

private val NA = "N/A"

private class DeviceManager(
        private val scanFreq: Long, mod: MOD
) : HidDeviceManager {
    private val hid = when (mod) {
        is HID -> HID_API
        is HIDRAW -> HID_API_RAW
        else -> throw IllegalArgumentException(
                "Incorrect hid type:$mod, supported only 'hid' or 'hidraw'")
    }

    private val listeners = CopyOnWriteArrayList<HidDeviceListener>()

    private val readyDevices = ConcurrentHashMap<String, Device>()

    private val scanner: Thread

    private val init = CountDownLatch(1)

    init {
        Runtime.getRuntime().addShutdownHook(
                object : Thread() {
                    override fun run() {
                        //reloadHid()
                    }
                })

        hid.hid_init()

        scanner = initScanner()

        scanner.isDaemon = true
    }

    private fun reloadHid() {
        val process = Runtime.getRuntime().exec("su")

        val stdin = process.outputStream
        val stdout = process.inputStream
        val stderr = process.errorStream

        //reload usbhid module
        stdin.write(("sudo modprobe -r usbhid; sudo modprobe usbhid\n").toByteArray())
        stdin.write("exit\n".toByteArray())

        stdin.flush()
        stdin.close()

        try {
            var line: String?

            var br = BufferedReader(InputStreamReader(stdout))

            while (true) {
                line = br.readLine() ?: break
                println("[Output] $line")
            }

            br.close()

            br = BufferedReader(InputStreamReader(stderr))

            while (true) {
                line = br.readLine() ?: break

                println("[Error] $line")
            }

            br.close()

        } catch (e: Exception) {

        } finally {
            process.waitFor()
            process.destroy()
        }
    }

    private fun initScanner(): Thread {
        return Thread({
            findDevices()

            init.countDown()

            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(scanFreq)

                    findDevices()

                } catch (e: InterruptedException) {

                    Thread.currentThread().interrupt()
                }
            }
        })
    }

    private fun findDevices() {
        val devices = scan()

        val deviceIds = HashSet<String>(devices.map { it.id })

        devices.forEach({ d ->
            val id = d.id

            if (!readyDevices.containsKey(id)) {
                listeners.forEach({ l -> l.onConnect(d) })

                readyDevices.put(id, d)
            }
        })

        readyDevices.forEach({ id, d ->
            if (!deviceIds.contains(id))
                listeners.forEach({ l -> l.onDisconnect(d) })

        })
    }

    private fun scan(): List<Device> {
        val devices = ArrayList<Device>()

        var devInfo = hid.hid_enumerate(0, 0)

        val root = devInfo

        try {
            devices += createDeviceWrapper(root)

            while (true) {
                devInfo = devInfo.next ?: break

                devices += createDeviceWrapper(devInfo)
            }
        } finally {
            hid.hid_free_enumeration(root.pointer)
        }

        return devices
    }

    private fun createDeviceWrapper(devInfo: DeviceInfo) = Device(
            id = devInfo.path ?: NA,
            serialNumber = devInfo.serial_number?.toString() ?: NA,
            product = devInfo.product_string?.toString() ?: NA,
            manufacturer = devInfo.manufacturer_string?.toString() ?: NA,
            vendorId = devInfo.vendor_id ?: -1,
            productId = devInfo.product_id ?: -1,
            releaseNumber = devInfo.release_number ?: -1,
            usagePage = devInfo.usage_page ?: -1,
            usage = devInfo.usage ?: -1,
            interfaceNumber = devInfo.interface_number ?: -1,
            hid = hid
    )

    override fun open(block: HidDeviceManager.() -> Unit) {
        scanner.start()

        init.await()

        use {
            block.invoke(this)
        }
    }

    override fun devices(): List<HidDevice> {
        val view = ArrayList<Device>(readyDevices.values)

        view.sortBy { it.id }

        return view
    }

    override fun device(id: String): HidDevice {
        val d = devices().firstOrNull { (it.id() == id) }

        return d ?: throw DeviceNotFound()
    }

    override fun device(vendorId: Short, productId: Short): HidDevice {
        val d = devices().firstOrNull { (it.vendorId() == vendorId) and (it.productId() == productId) }

        return d ?: throw DeviceNotFound()
    }

    override operator fun plusAssign(l: HidDeviceListener) {
        listeners += l
    }

    override operator fun minusAssign(l: HidDeviceListener) {
        listeners -= l
    }

    override fun close() {
        scanner.interrupt()

        scanner.join()

        val it = readyDevices.iterator()

        while (it.hasNext()) {
            val (_, dev) = it.next()

            if (dev.isOpen())
                dev.close()

            it.remove()
        }

        listeners.clear()

        assert(readyDevices.isEmpty())

        hid.hid_exit()
    }
}

private class Device(
        val id: String,
        val serialNumber: String,
        val product: String,
        val manufacturer: String,
        val vendorId: Short,
        val productId: Short,
        val releaseNumber: Short,
        val usagePage: Short,
        val usage: Short,
        val interfaceNumber: Int,
        private val hid: NativeHidApi,
        private val dev: NativeDevice = NativeDevice(),
        private val buf: Buffer = Buffer(),
        private val init: AtomicBoolean = AtomicBoolean(),
        private val bytes: Int = 1024
) : HidDevice {
    override fun id(): String = id
    override fun serialNumber(): String = serialNumber
    override fun product(): String = product
    override fun manufacturer(): String = manufacturer
    override fun vendorId(): Short = vendorId
    override fun productId(): Short = productId
    override fun releaseNumber(): Short = releaseNumber
    override fun usagePage(): Short = usagePage
    override fun usage(): Short = usage
    override fun interfaceNumber(): Int = interfaceNumber

    override fun open(): Boolean {
        return if (init.compareAndSet(false, true)) {
            dev.ptr = hid.hid_open_path(id)

            if (dev.ptr == null) {
                dev.ptr = hid.hid_open(vendorId, productId, if (NA == serialNumber) null else serialNumber)
            }

            if (dev.ptr != null) {
                println("HID device opened: ${vendorId.toHexString()} ${productId.toHexString()}")

                true
            } else
                false
        } else
            false
    }

    override fun open(block: HidDevice.() -> Unit) {
        if (open())
            this.use { block.invoke(this) }
    }

    override fun isOpen(): Boolean = init.get() and (dev.ptr != null)

    override fun nonBlocking(mod: Boolean): Int {
        validate()

        return hid.hid_set_nonblocking(dev.ptr!!, if (mod) 1 else 0)
    }

    override fun read(bytes: ByteArray, timeOut: Int): Int {
        if (timeOut < 0)
            throw IllegalArgumentException("Timeout can not be less 0.")

        validate()

        buf.buffer = bytes

        return if (timeOut == 0)
            hid.hid_read(dev.ptr!!, buf, bytes.size)
        else
            hid.hid_read_timeout(dev.ptr!!, buf, bytes.size, timeOut)
    }

    override fun error(): String {
        validate()

        buf.buffer = hid.hid_error(dev.ptr!!).getByteArray(0, bytes)

        return buf.toString()
    }

    override fun write(bytes: ByteArray): Int {
        validate()

        buf.buffer = bytes

        return hid.hid_write(dev.ptr!!, buf, bytes.size)
    }

    override fun close() {
        if (init.compareAndSet(true, false)) {
            if (dev.ptr != null) {
                hid.hid_close(dev.ptr!!)

                println("HID device closed: ${vendorId.toHexString()} ${productId.toHexString()}")
            }
        }
    }

    private fun validate() {
        if (!isOpen())
            throw IllegalStateException("Can not perform operation, device is not open.")
    }

    override fun toString(): String {
        return "HidDevice \n" +
                "   id='$id'\n" +
                "   serialNumber='$serialNumber'\n" +
                "   product='$product'\n" +
                "   manufacturer='$manufacturer'\n" +
                "   vendorId=${vendorId.toHexString()}\n" +
                "   productId=${productId.toHexString()}\n" +
                "   releaseNumber=${releaseNumber.toHexString()}\n" +
                "   usagePage=$usagePage\n" +
                "   usage=$usage\n" +
                "   interfaceNumber=$interfaceNumber\n"
    }

    private fun Short.toHexString(): String = "0x" + Integer.toHexString(this.toInt())
}

