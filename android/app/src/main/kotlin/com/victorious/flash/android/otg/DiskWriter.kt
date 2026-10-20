package com.victorious.flash.android.otg

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Splits the image into aligned chunks. The final chunk is zero-padded up to a whole block. */
object Chunker {
    const val DEFAULT_CHUNK = 1 shl 20 // 1 MiB: multiple of every supported block size, and of 4 KiB flash pages

    data class Chunk(val lba: Long, val blocks: Int, val dataBytes: Int)

    fun chunkBytes(blockSize: Int, preferred: Int = DEFAULT_CHUNK): Int {
        require(blockSize > 0 && preferred >= blockSize)
        val maxByCdb = Scsi.MAX_BLOCKS_10.toLong() * blockSize
        val c = minOf(preferred.toLong(), maxByCdb).toInt()
        return c - c % blockSize
    }

    fun blocksFor(bytes: Long, blockSize: Int) = (bytes + blockSize - 1) / blockSize

    fun plan(imageBytes: Long, blockSize: Int, chunk: Int = chunkBytes(blockSize)): Sequence<Chunk> {
        require(chunk % blockSize == 0)
        return generateSequence(0L) { it + chunk }.takeWhile { it < imageBytes }.map { pos ->
            val data = minOf(chunk.toLong(), imageBytes - pos).toInt()
            Chunk(pos / blockSize, blocksFor(data.toLong(), blockSize).toInt(), data)
        }
    }
}

fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

class WriteCancelled : IOException("Write cancelled")

/**
 * Raw dd-style writer with mandatory read-back verification. Success is only reported when the
 * SHA-256 of the bytes read back from the device equals the source hash (which itself must equal the expected hash).
 */
class DiskWriter(private val dev: BlockDevice, private val chunk: Int = Chunker.chunkBytes(dev.blockSize)) {
    enum class Phase { HASHING, WRITING, SYNCING, VERIFYING }
    data class Progress(val phase: Phase, val done: Long, val total: Long)

    sealed class Result {
        data class Verified(val sha256: String, val bytes: Long) : Result()
        data class SourceMismatch(val expected: String, val actual: String) : Result()
        data class TooSmall(val deviceBytes: Long, val imageBytes: Long) : Result()
        data class VerifyMismatch(val source: String, val readBack: String) : Result()
    }

    fun ensureFits(imageBytes: Long): Result.TooSmall? =
        if (imageBytes <= 0 || Chunker.blocksFor(imageBytes, dev.blockSize) > dev.blockCount) Result.TooSmall(dev.bytes, imageBytes) else null

    /**
     * [open] is called twice: once to pre-check the source hash (nothing is written on mismatch),
     * then to stream the data. The streamed bytes are hashed again so a file that changed in between is caught.
     */
    fun run(open: () -> InputStream, imageBytes: Long, expectedSha: String, cancelled: () -> Boolean = { false }, progress: (Progress) -> Unit = {}): Result {
        ensureFits(imageBytes)?.let { return it }
        val expected = expectedSha.trim().lowercase()
        val pre = hashStream(open(), imageBytes, cancelled) { progress(Progress(Phase.HASHING, it, imageBytes)) }
        if (pre != expected) return Result.SourceMismatch(expected, pre)

        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunk)
        var done = 0L
        open().use { input ->
            for (c in Chunker.plan(imageBytes, dev.blockSize, chunk)) {
                if (cancelled()) throw WriteCancelled()
                readFully(input, buf, c.dataBytes)
                md.update(buf, 0, c.dataBytes)
                val padded = c.blocks * dev.blockSize
                if (padded > c.dataBytes) buf.fill(0, c.dataBytes, padded)
                dev.write(c.lba, c.blocks, buf, 0)
                done += c.dataBytes
                progress(Progress(Phase.WRITING, done, imageBytes))
            }
        }
        val written = md.digest().hex()
        if (written != expected) return Result.SourceMismatch(expected, written)
        progress(Progress(Phase.SYNCING, imageBytes, imageBytes))
        dev.sync()
        val back = readBackSha(imageBytes, cancelled) { progress(Progress(Phase.VERIFYING, it, imageBytes)) }
        return if (back == written) Result.Verified(back, imageBytes) else Result.VerifyMismatch(written, back)
    }

    /** SHA-256 of the first [imageBytes] bytes on the device (padding in the last block is excluded). */
    fun readBackSha(imageBytes: Long, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunk)
        var done = 0L
        for (c in Chunker.plan(imageBytes, dev.blockSize, chunk)) {
            if (cancelled()) throw WriteCancelled()
            dev.read(c.lba, c.blocks, buf, 0)
            md.update(buf, 0, c.dataBytes)
            done += c.dataBytes
            progress(done)
        }
        return md.digest().hex()
    }

    private fun hashStream(input: InputStream, bytes: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): String = input.use {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            if (cancelled()) throw WriteCancelled()
            val n = it.read(buf)
            if (n < 0) break
            md.update(buf, 0, n); total += n; progress(total)
        }
        if (total != bytes) throw IOException("source is $total bytes, expected $bytes")
        md.digest().hex()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var o = 0
        while (o < len) { val n = input.read(buf, o, len - o); if (n < 0) throw IOException("source ended early"); o += n }
    }
}

/** Speed/ETA from a sliding start point. */
class RateMeter(private val now: () -> Long = System::nanoTime) {
    private var t0 = 0L; private var b0 = 0L; private var phase: Any? = null
    fun update(phase: Any, done: Long, total: Long): Pair<Double, Long?> {
        if (phase != this.phase) { this.phase = phase; t0 = now(); b0 = done }
        val secs = (now() - t0) / 1e9
        val bps = if (secs > 0.5) (done - b0) / secs else 0.0
        return bps to (if (bps > 0) ((total - done) / bps).toLong() else null)
    }
}
