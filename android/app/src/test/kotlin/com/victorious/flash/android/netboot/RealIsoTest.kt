package com.victorious.flash.android.netboot

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Optional: point VFLASH_REAL_ISO at a real (small) Ubuntu ISO, e.g. noble-mini-iso-amd64.iso. Skipped in CI. */
class RealIsoTest {
    @Test fun realUbuntuIso() {
        val path = System.getenv("VFLASH_REAL_ISO")
        assumeTrue(path != null && File(path).isFile)
        val f = File(path!!)
        Iso9660(f).use { iso ->
            println("root: " + iso.list("/")!!.joinToString { it.name })
            println("EFI/boot: " + iso.list("/EFI/boot")?.joinToString { it.name })
            println("casper: " + iso.list("/casper")?.joinToString { "${it.name}(${it.length})" })
        }
        val plan = EfiBoot.plan(f)
        println("missing: ${plan.missing}")
        plan.files.forEach { (s, e) ->
            val md = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(f, "r").use { r -> r.seek(e.offset); val b = ByteArray(1 shl 20); var left = e.length
                while (left > 0) { val n = r.read(b, 0, minOf(b.size.toLong(), left).toInt()); md.update(b, 0, n); left -= n } }
            println("$s ${e.length} sha256=" + md.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
