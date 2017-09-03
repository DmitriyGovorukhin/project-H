package com.flaidzeres.hid4kotlin

import java.nio.ByteBuffer
import java.util.*

fun main(args: Array<String>) {
    val lsnr = object : HidDeviceListener {
        override fun onConnect(d: HidDevice) {
            println("Connected: $d")
        }

        override fun onDisconnect(d: HidDevice) {
            println("Disconnected: $d")
        }
    }

    val mgr = HidDeviceManager(mod = HIDRAW)

    mgr += lsnr

    mgr.open {
        val d = device("/dev/hidraw1")

        d.open {
            val buf = ByteBuffer.allocate(64)

            val arr = buf.array()

            var cnt = 1000

            while (true) {
                if (cnt <= 0)
                    break

                read(arr)

                println(Arrays.toString(arr))

                buf.clear()

                cnt--
            }
        }
    }
}