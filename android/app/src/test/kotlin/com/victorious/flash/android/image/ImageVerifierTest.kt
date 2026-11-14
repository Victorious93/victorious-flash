package com.victorious.flash.android.image

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ImageVerifierTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun copyAndHash() {
        val out = ByteArrayOutputStream()
        val (h, n) = ImageVerifier.copyAndHash(ByteArrayInputStream("abc".toByteArray()), out)
        assertEquals(abc, h); assertEquals(3L, n); assertEquals("abc", out.toString())
    }

    @Test fun checksumFiles() {
        assertEquals(abc, ImageVerifier.findSha256("$abc *ubuntu.iso\n${"0".repeat(64)}  other.iso\n", "ubuntu.iso"))
        assertEquals(abc, ImageVerifier.findSha256("# x\nSHA256 (Fedora.iso) = $abc\n", "Fedora.iso"))
        assertNull(ImageVerifier.findSha256("$abc  a.iso", "b.iso"))
        assertFalse(ImageVerifier.isSha256("xyz"))
    }

    @Test fun perFileChecksumNamingADatedSnapshotIsUsableOnlyForItsOwnUrl() {
        val h = "a".repeat(64)
        val f = "$h  openSUSE-Tumbleweed-DVD-x86_64-Snapshot20261111-Media.iso\n"
        assertNull(ImageVerifier.findSha256(f, "openSUSE-Tumbleweed-DVD-x86_64-Current.iso"))
        assertEquals(h, ImageVerifier.singleEntry(f))
        assertTrue(ImageVerifier.isPerFileChecksum("https://x/a.iso", "https://x/a.iso.sha256"))
        assertFalse(ImageVerifier.isPerFileChecksum("https://x/a.iso", "https://x/SHA256SUMS"))
        assertNull("two entries are ambiguous", ImageVerifier.singleEntry("$h  a.iso\n${"b".repeat(64)}  b.iso\n"))
    }
}
