package com.victorious.flash.android.netboot

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class EfiBootTest {
    private fun tmp() = File.createTempFile("ubuntu", ".iso").apply { deleteOnExit() }

    private fun slice(f: File, e: Iso9660.Entry) = ByteArray(e.length.toInt()).also { RandomAccessFile(f, "r").use { r -> r.seek(e.offset); r.readFully(it) } }

    @Test fun readsRockRidgeAndPlainNames() {
        for (rr in listOf(true, false)) {
            val f = tmp(); val files = TestIso.ubuntuLike(f, withRockRidge = rr)
            Iso9660(f).use { iso ->
                files.forEach { (p, data) -> assertArrayEquals("$p rr=$rr", data, slice(f, iso.find(p)!!)) }
                assertEquals(setOf("efi", "casper"), iso.list("/")!!.map { it.name.lowercase() }.toSet())
                assertNull(iso.find("/casper"))          // directory, not file
                assertNull(iso.find("/nope/x"))
            }
        }
    }

    @Test fun rejectsNonIso() {
        val f = tmp().apply { writeBytes(ByteArray(40000)) }
        assertThrows(java.io.IOException::class.java) { Iso9660(f) }
        assertFalse(EfiBoot.plan(f).usable)
    }

    @Test fun planFindsUbuntuBootFiles() {
        val f = tmp(); TestIso.ubuntuLike(f)
        val p = EfiBoot.plan(f)
        assertTrue(p.missing.toString(), p.usable)
        assertEquals(setOf("/boot/bootx64.efi", "/boot/grubx64.efi", "/boot/mmx64.efi", "/casper/vmlinuz", "/casper/initrd"), p.files.keys)
    }

    @Test fun grubCfgContents() {
        val one = EfiBoot.grubCfg("http://192.168.42.129:8080/ubuntu.iso", null)
        assertTrue(one.contains("linux /casper/vmlinuz ip=dhcp iso-url=http://192.168.42.129:8080/ubuntu.iso cloud-config-url=/dev/null ---"))
        assertTrue(one.contains("initrd /casper/initrd"))
        assertFalse(one.contains("releases.ubuntu.com"))
        val two = EfiBoot.grubCfg("http://x/ubuntu.iso", "https://releases.ubuntu.com/24.04.4/ubuntu-24.04.4-desktop-amd64.iso")
        assertEquals(2, Regex("iso-url=").findAll(two).count())
        assertEquals(two.count { it == '{' }, two.count { it == '}' })
    }

    @Test fun officialUrlFilter() {
        assertNotNull(EfiBoot.officialUrlOrNull("https://releases.ubuntu.com/24.04.4/ubuntu-24.04.4-live-server-amd64.iso"))
        assertNull(EfiBoot.officialUrlOrNull("http://releases.ubuntu.com/x.iso"))
        assertNull(EfiBoot.officialUrlOrNull("https://evil.example/x.iso"))
        assertNull(EfiBoot.officialUrlOrNull("https://releases.ubuntu.com/x.iso quiet init=/bin/sh"))
    }

    /** Same route wiring as ServeService, exercised over real HTTP. */
    @Test fun servesBootFilesAndPerHostGrubCfg() {
        val f = tmp(); val files = TestIso.ubuntuLike(f)
        val plan = EfiBoot.plan(f)
        val routes = LinkedHashMap<String, Resource>()
        routes["/ubuntu.iso"] = Resource.Slice(f, 0, f.length(), IsoHttpServer.contentType(f.name))
        plan.files.forEach { (s, e) -> routes[s] = Resource.Slice(f, e.offset, e.length, IsoHttpServer.contentType(s)) }
        val cfg = Resource.Generated("text/plain") { hp -> EfiBoot.grubCfg("http://$hp/ubuntu.iso", null).toByteArray() }
        EfiBoot.grubCfgPaths.forEach { routes[it] = cfg }
        val srv = IsoHttpServer(routes, listOf(InetAddress.getByName("127.0.0.1"))).also { it.start() }
        try {
            fun get(p: String, range: String? = null) = (URL("http://127.0.0.1:${srv.boundPort}$p").openConnection() as HttpURLConnection).apply { range?.let { setRequestProperty("Range", it) } }
            val shim = get("/boot/bootx64.efi")
            assertEquals("application/efi", shim.contentType)
            assertArrayEquals(files["/EFI/boot/bootx64.efi"], shim.inputStream.readBytes())
            assertArrayEquals(files["/EFI/boot/grubx64.efi"], get("/boot/grubx64.efi").inputStream.readBytes())
            assertArrayEquals(files["/casper/initrd"], get("/casper/initrd").inputStream.readBytes())
            val k = get("/casper/vmlinuz", "bytes=100-199")
            assertEquals(206, k.responseCode)
            assertArrayEquals(files["/casper/vmlinuz"]!!.copyOfRange(100, 200), k.inputStream.readBytes())
            for (p in EfiBoot.grubCfgPaths) {
                val body = get(p).inputStream.readBytes().decodeToString()
                assertTrue(body.contains("iso-url=http://127.0.0.1:${srv.boundPort}/ubuntu.iso"))
            }
            assertEquals(404, get("/EFI/boot/bootx64.efi").responseCode) // only mapped paths
        } finally { srv.stop() }
    }
}
