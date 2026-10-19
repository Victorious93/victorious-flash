package com.victorious.flash.android.otg

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB Mass Storage Class, Bulk-Only Transport (BOT, "BBB") wrappers and the handful of SCSI commands
 * needed to write a raw image: INQUIRY, TEST UNIT READY, REQUEST SENSE, READ CAPACITY(10/16),
 * READ(10), WRITE(10), SYNCHRONIZE CACHE(10). Pure encoding/parsing, no Android types, so it is unit-tested.
 */
object Bot {
    const val CBW_SIGNATURE = 0x43425355 // "USBC" little-endian
    const val CSW_SIGNATURE = 0x53425355 // "USBS"
    const val CBW_LEN = 31
    const val CSW_LEN = 13

    enum class Dir { NONE, IN, OUT }

    fun buildCbw(tag: Int, dataLength: Int, dir: Dir, lun: Int, cdb: ByteArray): ByteArray {
        require(cdb.size in 1..16) { "CDB must be 1..16 bytes" }
        require(lun in 0..15)
        require(dataLength >= 0)
        require(dir == Dir.NONE == (dataLength == 0)) { "direction must be NONE iff no data" }
        val b = ByteBuffer.allocate(CBW_LEN).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(CBW_SIGNATURE).putInt(tag).putInt(dataLength)
        b.put(if (dir == Dir.IN) 0x80.toByte() else 0)
        b.put(lun.toByte()).put(cdb.size.toByte()).put(cdb)
        return b.array()
    }

    data class Csw(val tag: Int, val residue: Int, val status: Int) {
        val passed get() = status == STATUS_PASSED
        companion object { const val STATUS_PASSED = 0; const val STATUS_FAILED = 1; const val STATUS_PHASE_ERROR = 2 }
    }

    /** Parses a CSW and validates signature, length and tag (BOT spec 6.3: an invalid CSW requires reset recovery). */
    fun parseCsw(raw: ByteArray, len: Int = raw.size, expectedTag: Int): Csw {
        if (len != CSW_LEN) throw BotException("CSW has $len bytes, expected $CSW_LEN")
        val b = ByteBuffer.wrap(raw, 0, CSW_LEN).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != CSW_SIGNATURE) throw BotException("bad CSW signature")
        val tag = b.int
        if (tag != expectedTag) throw BotException("CSW tag mismatch: got $tag want $expectedTag")
        val residue = b.int
        val status = b.get().toInt() and 0xff
        if (status > 2) throw BotException("invalid CSW status $status")
        return Csw(tag, residue, status)
    }
}

class BotException(msg: String) : IOException(msg)

/** SCSI command check failed; carries sense data when it could be fetched. */
class ScsiException(msg: String, val sense: Scsi.Sense? = null) : IOException(msg + (sense?.let { " ($it)" } ?: ""))

object Scsi {
    const val INQUIRY = 0x12
    const val TEST_UNIT_READY = 0x00
    const val REQUEST_SENSE = 0x03
    const val READ_CAPACITY_10 = 0x25
    const val SERVICE_ACTION_IN_16 = 0x9E
    const val SA_READ_CAPACITY_16 = 0x10
    const val READ_10 = 0x28
    const val WRITE_10 = 0x2A
    const val SYNCHRONIZE_CACHE_10 = 0x35
    const val MAX_BLOCKS_10 = 0xFFFF
    const val MAX_LBA_10 = 0xFFFFFFFFL

    fun inquiry(alloc: Int = 36) = byteArrayOf(INQUIRY.toByte(), 0, 0, 0, alloc.toByte(), 0)
    fun testUnitReady() = ByteArray(6)
    fun requestSense(alloc: Int = 18) = byteArrayOf(REQUEST_SENSE.toByte(), 0, 0, 0, alloc.toByte(), 0)
    fun readCapacity10() = ByteArray(10).also { it[0] = READ_CAPACITY_10.toByte() }
    fun readCapacity16(alloc: Int = 32) = ByteArray(16).also {
        it[0] = SERVICE_ACTION_IN_16.toByte(); it[1] = SA_READ_CAPACITY_16.toByte(); putBe32(it, 10, alloc.toLong())
    }
    fun read10(lba: Long, blocks: Int) = rw10(READ_10, lba, blocks)
    fun write10(lba: Long, blocks: Int) = rw10(WRITE_10, lba, blocks)
    /** LBA 0 / count 0 = whole medium. */
    fun synchronizeCache10() = ByteArray(10).also { it[0] = SYNCHRONIZE_CACHE_10.toByte() }

    private fun rw10(op: Int, lba: Long, blocks: Int): ByteArray {
        require(lba in 0..MAX_LBA_10) { "LBA $lba needs a 16-byte CDB" }
        require(blocks in 1..MAX_BLOCKS_10) { "block count $blocks out of range for a 10-byte CDB" }
        require(lba + blocks - 1 <= MAX_LBA_10)
        val c = ByteArray(10); c[0] = op.toByte(); putBe32(c, 2, lba)
        c[7] = (blocks ushr 8).toByte(); c[8] = blocks.toByte()
        return c
    }

    data class Inquiry(val deviceType: Int, val removable: Boolean, val vendor: String, val product: String, val revision: String)

    fun parseInquiry(d: ByteArray, len: Int = d.size): Inquiry {
        if (len < 36) throw ScsiException("INQUIRY returned $len bytes")
        fun s(a: Int, b: Int) = String(d, a, b - a, Charsets.US_ASCII).trim()
        return Inquiry(d[0].toInt() and 0x1f, (d[1].toInt() and 0x80) != 0, s(8, 16), s(16, 32), s(32, 36))
    }

    data class Capacity(val lastLba: Long, val blockSize: Int) {
        val blockCount get() = lastLba + 1
        val bytes get() = blockCount * blockSize
    }

    /** Returns lastLba = 0xFFFFFFFF when the caller must retry with READ CAPACITY(16). */
    fun parseCapacity10(d: ByteArray, len: Int = d.size): Capacity {
        if (len < 8) throw ScsiException("READ CAPACITY(10) returned $len bytes")
        return Capacity(be32(d, 0), be32(d, 4).toInt()).also(::checkBlockSize)
    }

    fun parseCapacity16(d: ByteArray, len: Int = d.size): Capacity {
        if (len < 12) throw ScsiException("READ CAPACITY(16) returned $len bytes")
        val last = (be32(d, 0) shl 32) or be32(d, 4)
        return Capacity(last, be32(d, 8).toInt()).also(::checkBlockSize)
    }

    private fun checkBlockSize(c: Capacity) {
        if (c.blockSize !in listOf(512, 1024, 2048, 4096)) throw ScsiException("unsupported block size ${c.blockSize}")
    }

    data class Sense(val key: Int, val asc: Int, val ascq: Int) {
        override fun toString() = "sense key 0x%X, ASC 0x%02X, ASCQ 0x%02X".format(key, asc, ascq) + when {
            key == 0x7 -> ": medium is write-protected"
            key == 0x2 && asc == 0x3A -> ": no medium"
            key == 0x6 -> ": unit attention"
            else -> ""
        }
    }

    fun parseSense(d: ByteArray, len: Int = d.size): Sense? {
        if (len < 14) return null
        val code = d[0].toInt() and 0x7f
        if (code != 0x70 && code != 0x71) return null
        return Sense(d[2].toInt() and 0x0f, d[12].toInt() and 0xff, d[13].toInt() and 0xff)
    }

    fun putBe32(a: ByteArray, off: Int, v: Long) {
        a[off] = (v ushr 24).toByte(); a[off + 1] = (v ushr 16).toByte(); a[off + 2] = (v ushr 8).toByte(); a[off + 3] = v.toByte()
    }
    fun be32(a: ByteArray, off: Int): Long =
        ((a[off].toLong() and 0xff) shl 24) or ((a[off + 1].toLong() and 0xff) shl 16) or ((a[off + 2].toLong() and 0xff) shl 8) or (a[off + 3].toLong() and 0xff)
}
