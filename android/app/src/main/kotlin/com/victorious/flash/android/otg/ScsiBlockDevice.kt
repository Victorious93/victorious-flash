package com.victorious.flash.android.otg

import java.io.IOException

/** A [BlockDevice] on top of BOT + SCSI. Not thread-safe: one writer at a time. */
class ScsiBlockDevice(private val t: BulkTransport, private val lun: Int = 0, private val timeoutMs: Int = 20_000) : BlockDevice {
    private var tag = 1
    lateinit var inquiry: Scsi.Inquiry; private set
    lateinit var capacity: Scsi.Capacity; private set
    override val blockSize get() = capacity.blockSize
    override val blockCount get() = capacity.blockCount

    /** INQUIRY, wait for TEST UNIT READY (sticks often report a unit attention first), READ CAPACITY. */
    fun init(): ScsiBlockDevice {
        val inq = ByteArray(36)
        val n = command(Scsi.inquiry(36), Bot.Dir.IN, inq, 0, 36)
        inquiry = Scsi.parseInquiry(inq, n)
        if (inquiry.deviceType != 0) throw ScsiException("not a direct-access disk (SCSI type ${inquiry.deviceType})")
        var lastErr: IOException? = null
        for (i in 0 until 10) {
            try { command(Scsi.testUnitReady(), Bot.Dir.NONE, null, 0, 0); lastErr = null; break }
            catch (e: ScsiException) { lastErr = e; Thread.sleep(300) }
        }
        lastErr?.let { throw it }
        val c10 = ByteArray(8)
        var cap = Scsi.parseCapacity10(c10, command(Scsi.readCapacity10(), Bot.Dir.IN, c10, 0, 8))
        if (cap.lastLba == 0xFFFFFFFFL) {
            val c16 = ByteArray(32)
            cap = Scsi.parseCapacity16(c16, command(Scsi.readCapacity16(32), Bot.Dir.IN, c16, 0, 32))
        }
        capacity = cap
        return this
    }

    override fun read(lba: Long, blocks: Int, buf: ByteArray, off: Int) {
        checkRange(lba, blocks)
        val len = blocks * blockSize
        val n = command(Scsi.read10(lba, blocks), Bot.Dir.IN, buf, off, len)
        if (n != len) throw IOException("short read at LBA $lba: $n of $len bytes")
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, off: Int) {
        checkRange(lba, blocks)
        val len = blocks * blockSize
        val n = command(Scsi.write10(lba, blocks), Bot.Dir.OUT, buf, off, len)
        if (n != len) throw IOException("short write at LBA $lba: $n of $len bytes")
    }

    override fun sync() { command(Scsi.synchronizeCache10(), Bot.Dir.NONE, null, 0, 0, timeoutMs = 120_000) }

    private fun checkRange(lba: Long, blocks: Int) {
        if (lba < 0 || blocks <= 0 || lba + blocks > blockCount) throw IOException("LBA range $lba+$blocks outside device ($blockCount blocks)")
    }

    /** Runs one BOT transaction; returns bytes of data actually moved. Throws on transport or SCSI failure. */
    fun command(cdb: ByteArray, dir: Bot.Dir, buf: ByteArray?, off: Int, len: Int, timeoutMs: Int = this.timeoutMs): Int {
        val myTag = tag++
        val cbw = Bot.buildCbw(myTag, len, dir, lun, cdb)
        if (t.bulkOut(cbw, 0, cbw.size, timeoutMs) != cbw.size) { t.resetRecovery(); throw IOException(UNPLUG_HINT) }
        var moved = 0
        if (len > 0) {
            var stalled = false
            while (moved < len) {
                val r = if (dir == Bot.Dir.IN) t.bulkIn(buf!!, off + moved, len - moved, timeoutMs) else t.bulkOut(buf!!, off + moved, len - moved, timeoutMs)
                if (r < 0) { stalled = true; break }
                moved += r
                if (r == 0 || (dir == Bot.Dir.IN && r % 512 != 0)) break // short packet ends the data stage
            }
            if (stalled && !t.clearHalt(dir == Bot.Dir.IN)) { t.resetRecovery(); throw IOException(UNPLUG_HINT) }
        }
        val raw = ByteArray(Bot.CSW_LEN)
        var r = t.bulkIn(raw, 0, raw.size, timeoutMs)
        if (r < 0 && t.clearHalt(true)) r = t.bulkIn(raw, 0, raw.size, timeoutMs) // BOT 6.7.2: stall on CSW, clear and retry once
        val csw = try { Bot.parseCsw(raw, r, myTag) } catch (e: BotException) { t.resetRecovery(); throw if (r < 0) IOException(UNPLUG_HINT) else e }
        when (csw.status) {
            Bot.Csw.STATUS_PASSED -> return moved
            Bot.Csw.STATUS_PHASE_ERROR -> { t.resetRecovery(); throw BotException("phase error on SCSI op 0x%02X".format(cdb[0])) }
            else -> throw ScsiException("SCSI op 0x%02X failed".format(cdb[0]), if (cdb[0].toInt() == Scsi.REQUEST_SENSE) null else requestSense())
        }
    }

    private fun requestSense(): Scsi.Sense? = runCatching {
        val d = ByteArray(18)
        Scsi.parseSense(d, command(Scsi.requestSense(18), Bot.Dir.IN, d, 0, 18))
    }.getOrNull()

    companion object { const val UNPLUG_HINT = "USB transfer failed — the stick may have been unplugged or lost power" }
}
