package com.victorious.flash.android.image

import java.io.File
import java.security.MessageDigest

/**
 * Guards the image that is about to be (and then stays) exposed to the PC.
 *
 * Boot media must be the exact bytes the owner verified. [verify] re-hashes the file at expose
 * time against the TRUSTED expected SHA-256 (never against a hash computed from the same file),
 * and also fails if the file changed while it was being hashed. While exposed, [unchanged]
 * lets the service notice a modified or replaced file and restore USB instead of serving a moving target.
 */
object ImageGuard {
    data class Snapshot(val length: Long, val lastModified: Long)

    sealed class Result {
        data class Ok(val snapshot: Snapshot, val sha256: String) : Result()
        data class Refused(val reason: String) : Result()
    }

    fun snapshot(f: File) = Snapshot(f.length(), f.lastModified())
    fun unchanged(f: File, s: Snapshot): Boolean = f.isFile && snapshot(f) == s

    fun verify(f: File, expectedSha256: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result {
        val want = expectedSha256.trim().lowercase()
        if (!ImageVerifier.isSha256(want)) return Result.Refused("No trusted SHA-256 is available for this image, so it will not be exposed.")
        if (!f.isFile) return Result.Refused("The image file is gone.")
        val before = snapshot(f)
        if (before.length == 0L) return Result.Refused("The image file is empty.")
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        try {
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    onProgress(done, before.length)
                }
            }
        } catch (e: java.io.IOException) {
            return Result.Refused("The image could not be read: ${e.message}")
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (!unchanged(f, before) || done != before.length) return Result.Refused("The image changed while it was being verified.")
        if (got != want) return Result.Refused("The image does not match the trusted SHA-256 (expected $want, got $got). It was not exposed.")
        return Result.Ok(before, got)
    }
}
