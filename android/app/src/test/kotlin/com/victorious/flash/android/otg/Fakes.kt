package com.victorious.flash.android.otg

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** In-memory block device. [corrupt] lets a test simulate a fake/failing stick that silently mangles writes. */
class MemBlockDevice(override val blockSize: Int, override val blockCount: Long, var corrupt: ((Long, ByteArray) -> Unit)? = null) : BlockDevice {
    val data = ByteArray((blockSize * blockCount).toInt())
    val writes = mutableListOf<Pair<Long, Int>>()
    var syncs = 0
    var failWriteAtLba: Long? = null
    override fun read(lba: Long, blocks: Int, buf: ByteArray, off: Int) { check(lba, blocks); System.arraycopy(data, (lba * blockSize).toInt(), buf, off, blocks * blockSize) }
    override fun write(lba: Long, blocks: Int, buf: ByteArray, off: Int) {
        check(lba, blocks)
        if (failWriteAtLba == lba) throw IOException("USB transfer failed")
        writes += lba to blocks
        val chunk = buf.copyOfRange(off, off + blocks * blockSize)
        corrupt?.invoke(lba, chunk)
        System.arraycopy(chunk, 0, data, (lba * blockSize).toInt(), chunk.size)
    }
    override fun sync() { syncs++ }
    private fun check(lba: Long, blocks: Int) { if (lba < 0 || lba + blocks > blockCount) throw IOException("out of range") }
}

/** File-backed block device. */
class FileBlockDevice(f: File, override val blockSize: Int, override val blockCount: Long) : BlockDevice, AutoCloseable {
    private val raf = RandomAccessFile(f, "rw").apply { setLength(blockSize * blockCount) }
    override fun read(lba: Long, blocks: Int, buf: ByteArray, off: Int) { raf.seek(lba * blockSize); raf.readFully(buf, off, blocks * blockSize) }
    override fun write(lba: Long, blocks: Int, buf: ByteArray, off: Int) { raf.seek(lba * blockSize); raf.write(buf, off, blocks * blockSize) }
    override fun sync() { raf.fd.sync() }
    override fun close() = raf.close()
}

/** Simulates a USB stick's BOT/SCSI firmware over a [MemBlockDevice], speaking raw CBW/data/CSW bytes. */
class SimStick(val disk: MemBlockDevice, val vendor: String = "ACME", val product: String = "FlashKey") : BulkTransport {
    private var pendingIn: ByteArray? = null
    private var pendingOut: Pair<Long, Int>? = null
    private var outBuf = ByteArray(0); private var outPos = 0
    private var csw: ByteArray? = null
    private var tag = 0
    var unitAttentionsLeft = 1
    var unplugged = false
    val ops = mutableListOf<Int>()

    override fun bulkOut(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        if (unplugged) return -1
        pendingOut?.let { (lba, blocks) ->
            System.arraycopy(buf, off, outBuf, outPos, len); outPos += len
            if (outPos == outBuf.size) { disk.write(lba, blocks, outBuf, 0); pendingOut = null; csw = csw(0, 0) }
            return len
        }
        require(len == Bot.CBW_LEN)
        val b = ByteBuffer.wrap(buf, off, len).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == Bot.CBW_SIGNATURE); tag = b.int; val dl = b.int; b.get(); b.get(); val cl = b.get().toInt()
        val cdb = ByteArray(cl).also { b.get(it) }
        val op = cdb[0].toInt() and 0xff; ops += op
        fun lba() = Scsi.be32(cdb, 2); fun cnt() = ((cdb[7].toInt() and 0xff) shl 8) or (cdb[8].toInt() and 0xff)
        when (op) {
            Scsi.INQUIRY -> { val d = ByteArray(36); d[1] = 0x80.toByte(); "%-8s%-16s%-4s".format(vendor, product, "1.00").toByteArray().copyInto(d, 8); pendingIn = d; csw = csw(0, 0) }
            Scsi.TEST_UNIT_READY -> csw = if (unitAttentionsLeft-- > 0) csw(1, 0) else csw(0, 0)
            Scsi.REQUEST_SENSE -> { val d = ByteArray(18); d[0] = 0x70; d[2] = 6; d[12] = 0x28; pendingIn = d; csw = csw(0, 0) }
            Scsi.READ_CAPACITY_10 -> { val d = ByteArray(8); Scsi.putBe32(d, 0, disk.blockCount - 1); Scsi.putBe32(d, 4, disk.blockSize.toLong()); pendingIn = d; csw = csw(0, 0) }
            Scsi.READ_10 -> { val d = ByteArray(cnt() * disk.blockSize); disk.read(lba(), cnt(), d, 0); pendingIn = d; csw = csw(0, 0) }
            Scsi.WRITE_10 -> { require(dl == cnt() * disk.blockSize); pendingOut = lba() to cnt(); outBuf = ByteArray(dl); outPos = 0 }
            Scsi.SYNCHRONIZE_CACHE_10 -> { disk.sync(); csw = csw(0, 0) }
            else -> csw = csw(1, 0)
        }
        return len
    }

    override fun bulkIn(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        if (unplugged) return -1
        pendingIn?.let { d -> val n = minOf(len, d.size); System.arraycopy(d, 0, buf, off, n); pendingIn = null; return n }
        val c = csw ?: return -1
        csw = null; System.arraycopy(c, 0, buf, off, c.size); return c.size
    }

    private fun csw(status: Int, residue: Int) = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).putInt(Bot.CSW_SIGNATURE).putInt(tag).putInt(residue).put(status.toByte()).array()
}
