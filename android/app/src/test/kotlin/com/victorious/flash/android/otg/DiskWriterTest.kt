package com.victorious.flash.android.otg

import com.victorious.flash.android.otgConfirmOk
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

class DiskWriterTest {
    @Test fun chunkSizeIsAlignedAndBoundedByCdb() {
        for (bs in listOf(512, 1024, 2048, 4096)) { val c = Chunker.chunkBytes(bs); assertEquals(0, c % bs); assertEquals(1 shl 20, c) }
        assertEquals(0xFFFF * 512, Chunker.chunkBytes(512, 64 shl 20))
        assertEquals(0, Chunker.chunkBytes(512, 4096 + 100) % 512)
    }

    @Test fun planCoversImageExactlyWithPaddedTail() {
        val bs = 512; val chunk = 4096; val size = 3L * chunk + 700
        val p = Chunker.plan(size, bs, chunk).toList()
        assertEquals(4, p.size)
        assertEquals(listOf(0L, 8L, 16L, 24L), p.map { it.lba })
        assertEquals(size, p.sumOf { it.dataBytes.toLong() })
        assertEquals(2, p.last().blocks); assertEquals(700, p.last().dataBytes)
        p.forEach { assertEquals(0L, (it.lba * bs) % chunk) }
        assertTrue(Chunker.plan(0, bs, chunk).toList().isEmpty())
        assertEquals(1, Chunker.plan(4096, bs, chunk).count())
    }

    @Test fun verifiedWriteWithUnalignedSize() {
        val dev = MemBlockDevice(512, 20_000)
        val img = Random(2).nextBytes(5 * 1024 * 1024 + 333)
        dev.data.fill(0x5A)
        val phases = mutableSetOf<DiskWriter.Phase>()
        val r = DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), sha(img).uppercase()) { phases += it.phase }
        assertEquals(DiskWriter.Result.Verified(sha(img), img.size.toLong()), r)
        assertArrayEquals(img, dev.data.copyOf(img.size))
        assertTrue(dev.data.copyOfRange(img.size, ((img.size + 511) / 512) * 512).all { it.toInt() == 0 }) // padding zeroed
        assertEquals(0x5A.toByte(), dev.data[((img.size + 511) / 512) * 512]) // nothing beyond the image touched
        assertEquals(DiskWriter.Phase.values().toSet(), phases)
        assertEquals(1, dev.syncs)
    }

    @Test fun sourceMismatchWritesNothing() {
        val dev = MemBlockDevice(512, 1000)
        val img = Random(3).nextBytes(100_000)
        val r = DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), "0".repeat(64))
        assertTrue(r is DiskWriter.Result.SourceMismatch)
        assertTrue(dev.writes.isEmpty())
    }

    @Test fun deviceTooSmallIsRefused() {
        val dev = MemBlockDevice(512, 10)
        val img = ByteArray(5121)
        val r = DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), sha(img))
        assertEquals(DiskWriter.Result.TooSmall(5120, 5121), r)
        assertTrue(dev.writes.isEmpty())
        assertNull(DiskWriter(dev).ensureFits(5120))
    }

    @Test fun corruptingStickFailsVerification() {
        val dev = MemBlockDevice(512, 4000, corrupt = { lba, b -> if (lba == 2048L) b[17] = (b[17] + 1).toByte() })
        val img = Random(4).nextBytes(1_500_000)
        val r = DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), sha(img))
        assertTrue(r is DiskWriter.Result.VerifyMismatch)
        r as DiskWriter.Result.VerifyMismatch; assertEquals(sha(img), r.source); assertNotEquals(r.source, r.readBack)
    }

    @Test fun fakeCapacityStickThatWrapsFailsVerification() {
        // Classic counterfeit: writes beyond the real size wrap around onto the start.
        val real = 1024L * 1024
        val dev = object : BlockDevice by MemBlockDevice(512, 8192) {
            val mem = MemBlockDevice(512, real / 512)
            override fun read(lba: Long, blocks: Int, buf: ByteArray, off: Int) = mem.read(lba % mem.blockCount, blocks, buf, off)
            override fun write(lba: Long, blocks: Int, buf: ByteArray, off: Int) = mem.write(lba % mem.blockCount, blocks, buf, off)
        }
        val img = Random(5).nextBytes(3 * 1024 * 1024)
        assertTrue(DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), sha(img)) is DiskWriter.Result.VerifyMismatch)
    }

    @Test fun sourceChangingBetweenCheckAndWriteIsCaught() {
        val dev = MemBlockDevice(512, 1000)
        val img = Random(6).nextBytes(50_000); val other = img.copyOf().also { it[100] = (it[100] + 1).toByte() }
        var n = 0
        val r = DiskWriter(dev).run({ (if (n++ == 0) img else other).inputStream() }, img.size.toLong(), sha(img))
        assertTrue(r is DiskWriter.Result.SourceMismatch)
        assertEquals(0, dev.syncs)
    }

    @Test(expected = IOException::class) fun writeErrorPropagates() {
        val dev = MemBlockDevice(512, 10_000).apply { failWriteAtLba = 2048 }
        val img = Random(7).nextBytes(2 * 1024 * 1024)
        DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), sha(img))
    }

    @Test(expected = WriteCancelled::class) fun cancelStops() {
        val dev = MemBlockDevice(512, 10_000); val img = ByteArray(2 * 1024 * 1024)
        DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), sha(img), cancelled = { dev.writes.isNotEmpty() })
    }

    @Test fun fileBackedDevice4kBlocks() {
        val f = File.createTempFile("stick", ".img"); f.deleteOnExit()
        FileBlockDevice(f, 4096, 2048).use { dev ->
            val img = Random(8).nextBytes(3_000_001)
            assertTrue(DiskWriter(dev).run({ img.inputStream() }, img.size.toLong(), sha(img)) is DiskWriter.Result.Verified)
            assertEquals(sha(img), DiskWriter(dev).readBackSha(img.size.toLong()))
        }
    }

    @Test fun rateMeter() {
        var t = 0L; val m = RateMeter { t }
        m.update("w", 0, 100_000_000); t = 2_000_000_000
        val (bps, eta) = m.update("w", 20_000_000, 100_000_000)
        assertEquals(10_000_000.0, bps, 1.0); assertEquals(8L, eta)
    }

    @Test fun dangerConfirmNeedsBothTickAndWord() {
        assertFalse(otgConfirmOk("ERASE", false)); assertFalse(otgConfirmOk("erase", true)); assertFalse(otgConfirmOk("", true))
        assertTrue(otgConfirmOk(" ERASE ", true))
    }
}
