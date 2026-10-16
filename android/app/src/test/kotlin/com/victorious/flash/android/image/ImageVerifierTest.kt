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
}
