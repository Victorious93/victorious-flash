package com.victorious.flash.android.otg

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class ScsiBlockDeviceTest {
    @Test fun initReadsInquiryCapacityAndRetriesUnitAttention() {
        val sim = SimStick(MemBlockDevice(512, 4096))
        val d = ScsiBlockDevice(sim).init()
        assertEquals("ACME", d.inquiry.vendor); assertEquals("FlashKey", d.inquiry.product)
        assertEquals(4096L, d.blockCount); assertEquals(512, d.blockSize)
        assertTrue(Scsi.REQUEST_SENSE in sim.ops) // unit attention -> sense -> retry
    }

    @Test fun writeAndVerifyThroughBot() {
        val disk = MemBlockDevice(512, 8192)
        val d = ScsiBlockDevice(SimStick(disk)).init()
        val img = Random(1).nextBytes(3 * 1024 * 1024 + 100)
        val r = DiskWriter(d).run({ img.inputStream() }, img.size.toLong(), sha(img))
        assertTrue(r is DiskWriter.Result.Verified)
        assertArrayEquals(img, disk.data.copyOf(img.size))
        assertEquals(1, disk.syncs)
    }

    @Test(expected = IOException::class) fun unplugSurfacesAsIOException() {
        val sim = SimStick(MemBlockDevice(512, 64)); val d = ScsiBlockDevice(sim).init()
        sim.unplugged = true
        d.read(0, 1, ByteArray(512))
    }

    @Test(expected = IOException::class) fun refusesOutOfRange() { ScsiBlockDevice(SimStick(MemBlockDevice(512, 64))).init().write(63, 2, ByteArray(1024)) }
}

fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).hex()
