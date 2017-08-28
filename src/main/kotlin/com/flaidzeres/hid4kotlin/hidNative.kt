package com.flaidzeres.hid4kotlin

import com.sun.jna.*
import java.util.Arrays
import kotlin.experimental.or

var HID = Native.loadLibrary("hidapi", NativeHidApi::class.java) as NativeHidApi

interface NativeHidApi : Library {

    fun hid_init()

    fun hid_open(vendorId: Short, prdId: Short, serial: String?): Pointer

    fun hid_open_path(path: String): Pointer

    fun hid_error(device: Pointer): Pointer

    fun hid_close(device: Pointer)

    fun hid_exit()

    fun hid_read(device: Pointer, bytes: BufferRef, len: Int): Int

    fun hid_read_timeout(device: Pointer, bytes: BufferRef, len: Int, timeOut: Int): Int

    fun hid_write(device: Pointer, bytes: BufferRef, len: Int): Int

    fun hid_get_feature_report(device: Pointer, bytes: BufferRef, length: Int): Int

    fun hid_send_feature_report(device: Pointer, bytes: BufferRef, length: Int): Int

    fun hid_get_indexed_string(device: Pointer, idx: Int, str: BufferRef, len: Int): Int

    fun hid_get_manufacturer_string(device: Pointer, str: BufferRef, len: Int): Int

    fun hid_get_product_string(device: Pointer, str: BufferRef, len: Int): Int

    fun hid_get_serial_number_string(device: Pointer, str: BufferRef, len: Int): Int

    fun hid_set_nonblocking(device: Pointer, nonBlock: Int): Int

    fun hid_enumerate(vendorId: Short, productId: Short): DeviceInfo

    fun hid_free_enumeration(device: Pointer)
}

interface BufferRef : Structure.ByReference

class Buffer : Structure(), BufferRef {
    @JvmField
    var buffer: ByteArray? = null

    override fun getFieldOrder(): MutableList<String?> {
        return Arrays.asList<String>("buffer")
    }

    override fun toString(): String {
        return if (buffer != null) {
            val buf = buffer ?: ByteArray(0)
            val sb = StringBuilder()

            for (i in 0..buf.size) {
                sb.append((buf[i] or buf[i + 1].toInt().shl(8).toByte()).toChar())
            }

            return sb.toString()
        } else ""
    }
}

class Device : Structure(), Structure.ByReference {
    @JvmField
    var ptr: Pointer? = null

    override fun getFieldOrder(): MutableList<String?> {
        return Arrays.asList("ptr")
    }
}

class DeviceInfo : Structure(), Structure.ByReference {
    @JvmField
    var path: String? = null
    @JvmField
    var vendor_id: Short? = null
    @JvmField
    var product_id: Short? = null
    @JvmField
    var serial_number: WString? = null
    @JvmField
    var release_number: Short? = null
    @JvmField
    var manufacturer_string: WString? = null
    @JvmField
    var product_string: WString? = null
    @JvmField
    var usage_page: Short? = null
    @JvmField
    var usage: Short? = null
    @JvmField
    var interface_number: Int? = null
    @JvmField
    var next: DeviceInfo? = null

    override fun getFieldOrder(): MutableList<String> {
        return Arrays.asList<String>(
                "path",
                "vendor_id",
                "product_id",
                "serial_number",
                "release_number",
                "manufacturer_string",
                "product_string",
                "usage_page",
                "usage",
                "interface_number",
                "next"
        )
    }
}
