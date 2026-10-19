package com.victorious.flash.android.otg

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable
import java.io.IOException

/** Finds USB Mass Storage, SCSI transparent, Bulk-Only interfaces. Only devices attached to this phone's USB host port are visible here. */
object OtgDevices {
    fun massStorageInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map(d::getInterface).firstOrNull {
        it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
    }
    fun list(m: UsbManager): List<UsbDevice> = m.deviceList.values.filter { massStorageInterface(it) != null }
    fun label(d: UsbDevice) = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB device %04x:%04x".format(d.vendorId, d.productId) }
}

/** [BulkTransport] over Android's USB Host API. */
class UsbBulkTransport private constructor(private val conn: UsbDeviceConnection, private val iface: UsbInterface, private val inEp: UsbEndpoint, private val outEp: UsbEndpoint) : BulkTransport, Closeable {
    // Before API 28 a single bulkTransfer was capped at 16 KiB.
    private val maxXfer = if (Build.VERSION.SDK_INT >= 28) 1 shl 20 else 16384

    override fun bulkOut(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) = loop(outEp, buf, off, len, timeoutMs, false)
    override fun bulkIn(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) = loop(inEp, buf, off, len, timeoutMs, true)

    private fun loop(ep: UsbEndpoint, buf: ByteArray, off: Int, len: Int, t: Int, input: Boolean): Int {
        var done = 0
        while (done < len) {
            val want = minOf(maxXfer, len - done)
            val r = conn.bulkTransfer(ep, buf, off + done, want, t)
            if (r < 0) return if (done > 0) done else r
            done += r
            if (input && r < want) break // short packet
        }
        return done
    }

    override fun clearHalt(input: Boolean): Boolean =
        conn.controlTransfer(0x02, 0x01 /*CLEAR_FEATURE*/, 0 /*ENDPOINT_HALT*/, (if (input) inEp else outEp).address, null, 0, 2000) >= 0

    override fun resetRecovery(): Boolean {
        val ok = conn.controlTransfer(0x21, 0xFF /*Bulk-Only Mass Storage Reset*/, 0, iface.id, null, 0, 2000) >= 0
        clearHalt(true); clearHalt(false)
        return ok
    }

    override fun close() { runCatching { conn.releaseInterface(iface) }; conn.close() }

    companion object {
        fun open(m: UsbManager, d: UsbDevice): UsbBulkTransport {
            if (!m.hasPermission(d)) throw IOException("No USB permission for this stick")
            val iface = OtgDevices.massStorageInterface(d) ?: throw IOException("Not a USB mass-storage (Bulk-Only) device")
            var inEp: UsbEndpoint? = null; var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val e = iface.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
            }
            if (inEp == null || outEp == null) throw IOException("Mass-storage interface lacks bulk endpoints")
            val conn = m.openDevice(d) ?: throw IOException("Could not open the USB device")
            if (!conn.claimInterface(iface, true)) { conn.close(); throw IOException("Could not claim the USB interface") }
            return UsbBulkTransport(conn, iface, inEp, outEp)
        }
    }
}

data class OtgInfo(val deviceName: String, val label: String, val vendor: String, val product: String, val bytes: Long, val blockSize: Int) {
    val display get() = "$label ($vendor $product), ${humanBytes(bytes)}".replace("  ", " ")
}

fun humanBytes(b: Long): String = when {
    b >= 1_000_000_000L -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000L -> "%.1f MB".format(b / 1e6)
    else -> "$b bytes"
}

/** Opens the stick, reads INQUIRY + capacity, closes. Read-only. */
fun probeOtg(m: UsbManager, d: UsbDevice): OtgInfo = UsbBulkTransport.open(m, d).use { t ->
    val dev = ScsiBlockDevice(t).init()
    OtgInfo(d.deviceName, OtgDevices.label(d), dev.inquiry.vendor, dev.inquiry.product, dev.bytes, dev.blockSize)
}
