package com.victorious.flash.android.netboot

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Minimal read-only ISO 9660 reader (no root, no native code). Walks the primary volume
 * descriptor's directory tree, preferring Rock Ridge (NM) names, else ISO names with ";1"
 * stripped. Files are returned as (offset, length) slices of the image, so nothing has to be
 * copied: the HTTP server streams straight out of the verified ISO.
 */
class Iso9660(file: File) : Closeable {
    data class Entry(val name: String, val isDir: Boolean, val offset: Long, val length: Long)

    private val raf = RandomAccessFile(file, "r")
    private val blockSize: Int
    private val root: Entry

    init {
        val pvd = readAt(16L * 2048, 2048)
        if (pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") { raf.close(); throw IOException("Not an ISO 9660 image") }
        blockSize = u16(pvd, 128).takeIf { it in setOf(512, 1024, 2048) } ?: 2048
        root = parseRecord(pvd, 156)?.first ?: run { raf.close(); throw IOException("Bad root directory") }
    }

    /** Lists a directory (case-insensitive path, "/" for root). */
    fun list(path: String): List<Entry>? {
        val d = resolve(path) ?: return null
        return if (d.isDir) readDir(d) else null
    }

    /** Finds a file by case-insensitive path like "/EFI/boot/bootx64.efi". */
    fun find(path: String): Entry? = resolve(path)?.takeIf { !it.isDir }

    private fun resolve(path: String): Entry? {
        var cur = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            if (!cur.isDir) return null
            cur = readDir(cur).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return cur
    }

    private fun readDir(d: Entry): List<Entry> {
        if (d.length > 16L shl 20) throw IOException("Directory too large")
        val buf = readAt(d.offset, d.length.toInt())
        val out = mutableListOf<Entry>()
        var i = 0
        while (i < buf.size) {
            val len = buf[i].toInt() and 0xFF
            if (len == 0) { i = (i / blockSize + 1) * blockSize; continue } // records never span sectors
            val (e, special) = parseRecord(buf, i) ?: break
            if (!special) out += e
            i += len
        }
        return out
    }

    /** Returns the entry and whether it is "." or "..". */
    private fun parseRecord(b: ByteArray, at: Int): Pair<Entry, Boolean>? {
        val len = b[at].toInt() and 0xFF
        if (len < 34 || at + len > b.size) return null
        val extent = u32(b, at + 2); val size = u32(b, at + 10)
        val flags = b[at + 25].toInt()
        val nameLen = b[at + 32].toInt() and 0xFF
        if (33 + nameLen > len) return null
        val special = nameLen == 1 && (b[at + 33].toInt() == 0 || b[at + 33].toInt() == 1)
        var name = String(b, at + 33, nameLen, Charsets.ISO_8859_1)
        var su = at + 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
        // Rock Ridge alternate name (SUSP "NM" entries; concatenated if split).
        var rr: StringBuilder? = null
        while (su + 4 <= at + len) {
            val l = b[su + 2].toInt() and 0xFF
            if (l < 4 || su + l > at + len) break
            if (b[su] == 'N'.code.toByte() && b[su + 1] == 'M'.code.toByte() && l > 5) {
                (rr ?: StringBuilder().also { rr = it }).append(String(b, su + 5, l - 5, Charsets.UTF_8))
            }
            su += l
        }
        name = rr?.toString() ?: name.substringBefore(';').trimEnd('.').lowercase()
        return Entry(name, flags and 2 != 0, extent * blockSize, size) to special
    }

    private fun readAt(off: Long, n: Int): ByteArray {
        val b = ByteArray(n); raf.seek(off); raf.readFully(b); return b
    }

    override fun close() = raf.close()

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
        ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)
}
