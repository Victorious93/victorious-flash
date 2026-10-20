package com.victorious.flash.android.otg

import java.io.IOException

/** Raw block access to the target. The real one speaks BOT/SCSI over USB; tests use an in-memory/file fake. */
interface BlockDevice {
    val blockSize: Int
    val blockCount: Long
    val bytes: Long get() = blockCount * blockSize
    /** Reads [blocks] blocks at [lba] into buf[off ..]. */
    @Throws(IOException::class) fun read(lba: Long, blocks: Int, buf: ByteArray, off: Int = 0)
    @Throws(IOException::class) fun write(lba: Long, blocks: Int, buf: ByteArray, off: Int = 0)
    /** Flushes the device's write cache (SYNCHRONIZE CACHE). */
    @Throws(IOException::class) fun sync()
}

/** Byte-level transport the BOT layer runs over (USB bulk endpoints in the app, a simulator in tests). */
interface BulkTransport {
    /** Returns bytes transferred, or a negative value on failure. */
    fun bulkOut(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int
    fun bulkIn(buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int
    /** Clears a halted (stalled) endpoint; returns false if not possible. */
    fun clearHalt(input: Boolean): Boolean = false
    /** Bulk-Only Mass Storage Reset class request. */
    fun resetRecovery(): Boolean = false
}
