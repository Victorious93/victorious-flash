package com.victorious.flash.android.otg

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScsiTest {
    @Test fun cbwLayout() {
        val cdb = Scsi.read10(0x01020304, 0x0800)
        val c = Bot.buildCbw(0x11223344, 0x100000, Bot.Dir.IN, 0, cdb)
        assertEquals(31, c.size)
        assertArrayEquals(byteArrayOf(0x55, 0x53, 0x42, 0x43), c.copyOfRange(0, 4)) // "USBC"
        assertArrayEquals(byteArrayOf(0x44, 0x33, 0x22, 0x11), c.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0, 0, 0x10, 0), c.copyOfRange(8, 12))
        assertEquals(0x80.toByte(), c[12]); assertEquals(0, c[13].toInt()); assertEquals(10, c[14].toInt())
        assertArrayEquals(cdb, c.copyOfRange(15, 25))
        assertTrue(c.copyOfRange(25, 31).all { it.toInt() == 0 })
    }

    @Test fun cbwOutAndNoneFlags() {
        assertEquals(0, Bot.buildCbw(1, 512, Bot.Dir.OUT, 0, Scsi.write10(0, 1))[12].toInt())
        assertEquals(0, Bot.buildCbw(1, 0, Bot.Dir.NONE, 0, Scsi.testUnitReady())[12].toInt())
    }

    @Test(expected = IllegalArgumentException::class) fun cbwRejectsDirWithoutData() { Bot.buildCbw(1, 0, Bot.Dir.IN, 0, Scsi.testUnitReady()) }

    private fun csw(sig: Int, tag: Int, res: Int, st: Int) = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).putInt(sig).putInt(tag).putInt(res).put(st.toByte()).array()

    @Test fun cswParse() {
        val c = Bot.parseCsw(csw(Bot.CSW_SIGNATURE, 7, 512, 0), expectedTag = 7)
        assertTrue(c.passed); assertEquals(512, c.residue)
        assertEquals(Bot.Csw.STATUS_FAILED, Bot.parseCsw(csw(Bot.CSW_SIGNATURE, 7, 0, 1), expectedTag = 7).status)
    }

    @Test(expected = BotException::class) fun cswBadSignature() { Bot.parseCsw(csw(0x12345678, 7, 0, 0), expectedTag = 7) }
    @Test(expected = BotException::class) fun cswBadTag() { Bot.parseCsw(csw(Bot.CSW_SIGNATURE, 8, 0, 0), expectedTag = 7) }
    @Test(expected = BotException::class) fun cswShort() { Bot.parseCsw(csw(Bot.CSW_SIGNATURE, 7, 0, 0), 12, 7) }
    @Test(expected = BotException::class) fun cswBadStatus() { Bot.parseCsw(csw(Bot.CSW_SIGNATURE, 7, 0, 3), expectedTag = 7) }

    @Test fun cdbs() {
        assertArrayEquals(byteArrayOf(0x2A, 0, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0, 0xFF.toByte(), 0xFF.toByte(), 0), Scsi.write10(0xDEADBEEFL, 0xFFFF))
        assertEquals(0x28, Scsi.read10(0, 1)[0].toInt())
        assertArrayEquals(byteArrayOf(0x12, 0, 0, 0, 36, 0), Scsi.inquiry())
        assertArrayEquals(ByteArray(6), Scsi.testUnitReady())
        assertEquals(0x35, Scsi.synchronizeCache10()[0].toInt()); assertEquals(10, Scsi.synchronizeCache10().size)
        val rc16 = Scsi.readCapacity16(32); assertEquals(0x9E.toByte(), rc16[0]); assertEquals(0x10, rc16[1].toInt()); assertEquals(32, rc16[13].toInt()); assertEquals(16, rc16.size)
        assertEquals(0x25, Scsi.readCapacity10()[0].toInt())
    }

    @Test(expected = IllegalArgumentException::class) fun rw10RejectsZeroBlocks() { Scsi.write10(0, 0) }
    @Test(expected = IllegalArgumentException::class) fun rw10RejectsTooManyBlocks() { Scsi.write10(0, 0x10000) }
    @Test(expected = IllegalArgumentException::class) fun rw10RejectsHugeLba() { Scsi.read10(0x100000000L, 1) }

    @Test fun capacity10And16() {
        val d = ByteArray(8); Scsi.putBe32(d, 0, 0x01DCFFFF); Scsi.putBe32(d, 4, 512)
        val c = Scsi.parseCapacity10(d); assertEquals(0x01DD0000L, c.blockCount); assertEquals(0x01DD0000L * 512, c.bytes)
        val e = ByteArray(32); Scsi.putBe32(e, 0, 1); Scsi.putBe32(e, 4, 0xFFFFFFFFL); Scsi.putBe32(e, 8, 4096)
        val c16 = Scsi.parseCapacity16(e); assertEquals(0x1FFFFFFFFL, c16.lastLba); assertEquals(4096, c16.blockSize)
    }

    @Test(expected = ScsiException::class) fun capacityRejectsOddBlockSize() { val d = ByteArray(8); Scsi.putBe32(d, 4, 520); Scsi.parseCapacity10(d) }

    @Test fun inquiryAndSense() {
        val d = ByteArray(36); d[1] = 0x80.toByte(); "SanDisk Cruzer Blade     1.00".toByteArray().copyInto(d, 8)
        val i = Scsi.parseInquiry(d); assertEquals("SanDisk", i.vendor); assertEquals("Cruzer Blade", i.product); assertTrue(i.removable); assertEquals(0, i.deviceType)
        val s = ByteArray(18); s[0] = 0x70; s[2] = 7; s[12] = 0x27
        assertEquals(7, Scsi.parseSense(s)!!.key); assertTrue(Scsi.parseSense(s).toString().contains("write-protected"))
        assertNull(Scsi.parseSense(ByteArray(18)))
    }
}
