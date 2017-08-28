package com.flaidzeres.hid4kotlin

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList


interface HidDeviceListener {
    fun onConnect(d: HidDevice)

    fun onDisconnect(d: HidDevice)
}

private val NA = "N/A"

class HidConfiguration(val scanFreq: Long = 2000L)

class HidDeviceManager(
        private val hidConfig: HidConfiguration = HidConfiguration()
) {
    private val hid = HID

    private val listeners = CopyOnWriteArrayList<HidDeviceListener>()

    private val readyDevices = ConcurrentHashMap<String, HidDevice>()

    private val init = AtomicBoolean()

    private var scanner: Thread? = null

    private var initLatch: CountDownLatch? = null

    init {
        Runtime.getRuntime().addShutdownHook(
                object : Thread() {
                    override fun run() {
                        reloadHid()
                    }
                })
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
            while (!Thread.interrupted()) {
                try {
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
                        if (!deviceIds.contains(id)) {

                            listeners.forEach({ l -> l.onDisconnect(d) })
                        } else
                            deviceIds -= id
                    })

                    if (init.compareAndSet(false, true))
                        initLatch!!.countDown()

                    Thread.sleep(hidConfig.scanFreq)
                } catch (e: InterruptedException) {

                    Thread.currentThread().interrupt()
                }
            }
        })
    }

    private fun scan(): List<HidDevice> {
        val devices = ArrayList<HidDevice>()

        var devInfo = hid.hid_enumerate(0, 0)

        val root = devInfo

        try {
            devices += createHidDevice(root)

            while (true) {
                devInfo = devInfo.next ?: break

                devices += createHidDevice(devInfo)
            }
        } finally {
            hid.hid_free_enumeration(root.pointer)
        }

        return devices
    }

    private fun createHidDevice(devInfo: DeviceInfo) = HidDevice(
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

    fun devices(): List<HidDevice> {
        val view = ArrayList<HidDevice>(readyDevices.values)

        view.sortBy { it.id }

        return view
    }

    fun device(vendorId: Short, productId: Short): HidDevice? {
        return devices().firstOrNull { (it.vendorId == vendorId) and (it.productId == productId) }
    }

    fun addListener(l: HidDeviceListener) {
        listeners += l
    }

    fun removeListener(l: HidDeviceListener) {
        listeners -= l
    }

    operator fun plusAssign(l: HidDeviceListener) {
        addListener(l)
    }

    operator fun minusAssign(l: HidDeviceListener) {
        removeListener(l)
    }

    fun start() {
        hid.hid_init()

        initLatch = CountDownLatch(1)

        scanner = initScanner()

        scanner!!.start()

        initLatch!!.await()
    }

    fun stop() {
        val needReload = if (init.compareAndSet(false, true)) {
            initLatch?.countDown()

            true
        } else false

        scanner?.interrupt()

        scanner?.join()

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

        if (needReload) reloadHid()
    }
}

class HidDevice(
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
        private val dev: Device = Device(),
        private val buf: Buffer = Buffer(),
        private val init: AtomicBoolean = AtomicBoolean(),
        private val bytes: Int = 1024
) {
    fun open(): Boolean {
        return if (init.compareAndSet(false, true)) {
            dev.ptr = hid.hid_open_path(id)

            if (dev.ptr == null) {
                dev.ptr = hid.hid_open(vendorId, productId, if (NA == serialNumber) null else serialNumber)
            }

            if (dev.ptr != null) {
                println("Device opened: ${vendorId.toHexString()} ${productId.toHexString()}")

                true
            } else
                false
        } else
            false
    }

    fun isOpen(): Boolean = init.get() and (dev.ptr != null)

    fun nonBlocking(f: Boolean): Int {
        validate()

        return hid.hid_set_nonblocking(dev.ptr!!, if (f) 1 else 0)
    }

    fun read(bytes: ByteArray, timeOut: Int = 0): Int {
        if (timeOut < 0)
            throw IllegalArgumentException("Timeout can not be less 0.")

        validate()

        buf.buffer = bytes

        return if (timeOut == -1)
            hid.hid_read(dev.ptr!!, buf, bytes.size)
        else
            hid.hid_read_timeout(dev.ptr!!, buf, bytes.size, timeOut)
    }

    fun error(): String {
        validate()

        buf.buffer = hid.hid_error(dev.ptr!!).getByteArray(0, bytes)

        return buf.toString()
    }

    fun write(bytes: ByteArray): Int {
        validate()

        buf.buffer = bytes

        return hid.hid_write(dev.ptr!!, buf, bytes.size)
    }

    fun close() {
        if (init.compareAndSet(true, false)) {
            if (dev.ptr != null) {
                hid.hid_close(dev.ptr!!)

                println("Device closed: ${vendorId.toHexString()} ${productId.toHexString()}")
            }
        }
    }

    private fun validate() {
        if (!isOpen())
            throw IllegalStateException("Can not perform operation, device is not open.")
    }

    override fun toString(): String {
        return "HidDevice[id='$id', serialNumber='$serialNumber', " +
                "product='$product', manufacturer='$manufacturer', " +
                "vendorId=${vendorId.toHexString()}, productId=${productId.toHexString()}, " +
                "releaseNumber=$releaseNumber, usagePage=$usagePage, usage=$usage, " +
                "interfaceNumber=$interfaceNumber]"
    }

    private fun Short.toHexString(): String = "0x"+Integer.toHexString(this.toInt())
}

