package com.victorious.flash.android.image

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ImageGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun image(bytes: ByteArray = ByteArray(3_000_000) { (it * 31).toByte() }) = tmp.newFile("v.iso").also { it.writeBytes(bytes) }

    @Test fun matchingTrustedHashIsAcceptedWithUppercaseInput() {
        val b = ByteArray(2_500_000) { it.toByte() }; val f = image(b)
        val r = ImageGuard.verify(f, sha(b).uppercase())
        assertTrue(r is ImageGuard.Result.Ok)
        assertEquals(sha(b), (r as ImageGuard.Result.Ok).sha256)
    }

    @Test fun wrongHashIsRefusedAndNamedAsSuch() {
        val f = image()
        val r = ImageGuard.verify(f, "0".repeat(64))
        assertTrue(r is ImageGuard.Result.Refused)
        assertTrue((r as ImageGuard.Result.Refused).reason.contains("does not match the trusted SHA-256"))
    }

    @Test fun missingOrMalformedTrustIsRefusedBeforeReadingAnything() {
        val f = image()
        for (bad in listOf("", "abc", "g".repeat(64))) assertTrue(ImageGuard.verify(f, bad) is ImageGuard.Result.Refused)
    }

    @Test fun emptyAndMissingFilesAreRefused() {
        val e = tmp.newFile("empty.iso")
        assertTrue(ImageGuard.verify(e, "0".repeat(64)) is ImageGuard.Result.Refused)
        assertTrue(ImageGuard.verify(java.io.File(tmp.root, "gone.iso"), "0".repeat(64)) is ImageGuard.Result.Refused)
    }

    @Test fun fileChangedWhileHashingIsRefused() {
        val b = ByteArray(4_000_000) { 7 }; val f = image(b)
        var touched = false
        val r = ImageGuard.verify(f, sha(b)) { d, _ -> if (!touched && d > 1_000_000) { touched = true; f.appendBytes(byteArrayOf(1)); f.setLastModified(f.lastModified() + 5000) } }
        assertTrue(r is ImageGuard.Result.Refused)
        assertTrue((r as ImageGuard.Result.Refused).reason.contains("changed while"))
    }

    @Test fun unchangedDetectsModificationReplacementAndDeletion() {
        val f = image(); val s = ImageGuard.snapshot(f)
        assertTrue(ImageGuard.unchanged(f, s))
        f.setLastModified(f.lastModified() + 10_000); assertFalse(ImageGuard.unchanged(f, s))
        val s2 = ImageGuard.snapshot(f); f.appendBytes(byteArrayOf(0)); assertFalse(ImageGuard.unchanged(f, s2))
        val s3 = ImageGuard.snapshot(f); f.delete(); assertFalse(ImageGuard.unchanged(f, s3))
    }
}
