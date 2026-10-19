package com.victorious.flash.android.netboot

import java.io.ByteArrayOutputStream
import java.io.File

/** Builds a tiny ISO 9660 image (with optional Rock Ridge NM names) shaped like an Ubuntu ISO. */
object TestIso {
    sealed class Node(val isoName: String, val rrName: String?)
    class Dir(isoName: String, rrName: String?, val children: List<Node>) : Node(isoName, rrName)
    class F(isoName: String, rrName: String?, val data: ByteArray) : Node(isoName, rrName)

    private fun record(name: ByteArray, extent: Int, size: Int, dir: Boolean, rr: String?): ByteArray {
        val su = rr?.toByteArray()?.let { byteArrayOf('N'.code.toByte(), 'M'.code.toByte(), (5 + it.size).toByte(), 1, 0) + it } ?: ByteArray(0)
        val pad = if (name.size % 2 == 0) 1 else 0
        var len = 33 + name.size + pad + su.size
        if (len % 2 == 1) len++
        val b = ByteArray(len)
        b[0] = len.toByte()
        le32(b, 2, extent); be32(b, 6, extent); le32(b, 10, size); be32(b, 14, size)
        b[25] = if (dir) 2 else 0
        b[28] = 1; b[31] = 1 // volume sequence number = 1 (LE / BE)
        b[32] = name.size.toByte()
        name.copyInto(b, 33)
        su.copyInto(b, 33 + name.size + pad)
        return b
    }

    fun build(out: File, root: Dir) {
        val sectors = HashMap<Int, ByteArray>()
        var next = 20
        val extents = HashMap<Node, Int>(); val sizes = HashMap<Node, Int>()
        fun alloc(n: Node) {
            val size = if (n is F) n.data.size else 2048
            extents[n] = next; sizes[n] = size
            next += maxOf(1, (size + 2047) / 2048)
            if (n is Dir) n.children.forEach(::alloc)
        }
        alloc(root)
        fun write(n: Node, parent: Dir) {
            if (n is F) { sectors[extents[n]!!] = n.data; return }
            n as Dir
            val bo = ByteArrayOutputStream()
            bo.write(record(byteArrayOf(0), extents[n]!!, 2048, true, null))
            bo.write(record(byteArrayOf(1), extents[parent]!!, 2048, true, null))
            n.children.forEach { c -> bo.write(record(c.isoName.toByteArray(), extents[c]!!, sizes[c]!!, c is Dir, c.rrName)) }
            require(bo.size() <= 2048)
            sectors[extents[n]!!] = bo.toByteArray()
            n.children.forEach { write(it, n) }
        }
        write(root, root)
        val pvd = ByteArray(2048)
        pvd[0] = 1; "CD001".toByteArray().copyInto(pvd, 1); pvd[6] = 1
        pvd[128] = 0; pvd[129] = 8 // 2048 LE
        record(byteArrayOf(0), extents[root]!!, 2048, true, null).copyInto(pvd, 156)
        sectors[16] = pvd
        sectors[17] = ByteArray(2048).also { it[0] = 255.toByte(); "CD001".toByteArray().copyInto(it, 1); it[6] = 1 }
        val img = ByteArray(next * 2048)
        sectors.forEach { (s, d) -> d.copyInto(img, s * 2048) }
        out.writeBytes(img)
    }

    /** Ubuntu-shaped layout; [kernel] etc. are distinct byte patterns. */
    fun ubuntuLike(out: File, withRockRidge: Boolean = true): Map<String, ByteArray> {
        fun rr(s: String) = if (withRockRidge) s else null
        val files = mapOf(
            "/EFI/boot/bootx64.efi" to ByteArray(3000) { 1 }, "/EFI/boot/grubx64.efi" to ByteArray(5000) { 2 },
            "/EFI/boot/mmx64.efi" to ByteArray(100) { 3 }, "/casper/vmlinuz" to ByteArray(9000) { (it % 251).toByte() },
            "/casper/initrd" to ByteArray(4097) { 5 },
        )
        build(out, Dir("", null, listOf(
            Dir("EFI", rr("EFI"), listOf(Dir("BOOT", rr("boot"), listOf(
                F("BOOTX64.EFI;1", rr("bootx64.efi"), files["/EFI/boot/bootx64.efi"]!!),
                F("GRUBX64.EFI;1", rr("grubx64.efi"), files["/EFI/boot/grubx64.efi"]!!),
                F("MMX64.EFI;1", rr("mmx64.efi"), files["/EFI/boot/mmx64.efi"]!!),
            )))),
            Dir("CASPER", rr("casper"), listOf(
                F("VMLINUZ.;1", rr("vmlinuz"), files["/casper/vmlinuz"]!!),
                F("INITRD.;1", rr("initrd"), files["/casper/initrd"]!!),
            )),
        )))
        return files
    }

    private fun le32(b: ByteArray, i: Int, v: Int) { for (k in 0..3) b[i + k] = (v ushr (8 * k)).toByte() }
    private fun be32(b: ByteArray, i: Int, v: Int) { for (k in 0..3) b[i + k] = (v ushr (8 * (3 - k))).toByte() }
}
