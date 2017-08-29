package com.flaidzeres.hid4kotlin

import java.nio.ByteBuffer
import java.util.*

fun main(args: Array<String>) {
    val mgr1 = HidDeviceManager(HidConfiguration(mod = "hid"))

    val l1 = object : HidDeviceListener {
        override fun onConnect(d: HidDevice) {
            println("Device connected: ${d.id}")
        }

        override fun onDisconnect(d: HidDevice) {
            println("Device disconnected: ${d.id}")
        }
    }

    mgr1 += l1

    mgr1.start()

    printDevices(mgr1)

    mgr1 -= l1

    mgr1.stop()

    val mgr2 = HidDeviceManager(HidConfiguration(mod = "hidraw"))

    val l2 = object : HidDeviceListener {
        override fun onConnect(d: HidDevice) {
            println("Device connected: ${d.id}")
        }

        override fun onDisconnect(d: HidDevice) {
            println("Device disconnected: ${d.id}")
        }
    }

    mgr2 += l2

    mgr2.start()

    printDevices(mgr2)

    mgr2 -= l2

    mgr2.stop()
}

private fun printDevices(mgr: HidDeviceManager) {
    for (d in mgr.devices())
        println(d)
}

private fun readKeyBoard(mgr: HidDeviceManager) {
    val d = mgr.device(0x04d9, 0x1702)

    if (d != null) {
        if (d.open())
            println("Opened")

        println(d)

        try {
            if (d.isOpen()) {
                d.nonBlocking(true)

                val end = System.currentTimeMillis() + 10_000

                val buf = ByteBuffer.allocate(16)

                while (true) {
                    val arr = buf.array()

                    val read = d.read(arr)

                    if (read != 0) {
                        println("read:$read " + Arrays.toString(arr))
                    } else
                        println("Nothing to read")

                    buf.clear()

                    Thread.sleep(100)

                    if (System.currentTimeMillis() - end > 0)
                        break
                }
            }
        } finally {
            d.close()
        }
    }
}