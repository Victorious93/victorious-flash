package com.victorious.flash.android.netboot

import com.victorious.flash.android.netboot.IsoHttpServer.RangeResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import kotlin.random.Random

class IsoHttpServerTest {
    private lateinit var file: File
    private lateinit var data: ByteArray
    private lateinit var server: IsoHttpServer
    private val base get() = "http://127.0.0.1:${server.boundPort}"

    @Before fun setUp() {
        data = Random(42).nextBytes(3 * 1024 * 1024 + 17)
        file = File.createTempFile("test", ".iso").apply { writeBytes(data); deleteOnExit() }
        server = IsoHttpServer(file, "/ubuntu.iso", listOf(InetAddress.getByName("127.0.0.1"))).also { it.start() }
    }
    @After fun tearDown() { server.stop(); file.delete() }

    private fun get(path: String, range: String? = null, method: String = "GET"): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply { requestMethod = method; range?.let { setRequestProperty("Range", it) } }

    @Test fun fullGet() {
        val c = get("/ubuntu.iso")
        assertEquals(200, c.responseCode)
        assertEquals("application/vnd.efi.iso", c.contentType)
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertEquals(data.size.toLong(), c.getHeaderField("Content-Length").toLong())
        assertArrayEquals(data, c.inputStream.readBytes())
    }

    @Test fun rangeGet() {
        val c = get("/ubuntu.iso", "bytes=1000-1999")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 1000-1999/${data.size}", c.getHeaderField("Content-Range"))
        assertArrayEquals(data.copyOfRange(1000, 2000), c.inputStream.readBytes())
    }

    @Test fun openAndSuffixRanges() {
        val open = get("/ubuntu.iso", "bytes=${data.size - 10}-")
        assertEquals(206, open.responseCode)
        assertArrayEquals(data.copyOfRange(data.size - 10, data.size), open.inputStream.readBytes())
        val suffix = get("/ubuntu.iso", "bytes=-5")
        assertArrayEquals(data.copyOfRange(data.size - 5, data.size), suffix.inputStream.readBytes())
    }

    @Test fun unsatisfiableRange() {
        val c = get("/ubuntu.iso", "bytes=${data.size}-")
        assertEquals(416, c.responseCode)
        assertEquals("bytes */${data.size}", c.getHeaderField("Content-Range"))
    }

    @Test fun headHasLengthNoBody() {
        val c = get("/ubuntu.iso", method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals(data.size.toLong(), c.getHeaderField("Content-Length").toLong())
    }

    @Test fun onlyTheOnePathIsServed() {
        assertEquals(404, get("/").responseCode)
        assertEquals(404, get("/../etc/passwd").responseCode)
        assertEquals(404, get("/ubuntu.iso/x").responseCode)
        assertEquals(405, get("/ubuntu.iso", method = "PUT").responseCode)
    }

    @Test fun keepAliveTwoRequestsOneSocket() {
        Socket("127.0.0.1", server.boundPort).use { s ->
            val out = s.getOutputStream(); val inp = s.getInputStream()
            fun req(r: String): String {
                out.write("GET /ubuntu.iso HTTP/1.1\r\nHost: x\r\nRange: bytes=$r\r\n\r\n".toByteArray()); out.flush()
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) sb.append(inp.read().toChar())
                val len = Regex("Content-Length: (\\d+)").find(sb)!!.groupValues[1].toInt()
                repeat(len) { inp.read() }
                return sb.lines().first()
            }
            assertEquals("HTTP/1.1 206 Partial Content", req("0-9"))
            assertEquals("HTTP/1.1 206 Partial Content", req("10-19"))
        }
    }

    @Test fun parseRangeRules() {
        assertEquals(RangeResult.Ok(0, 99), IsoHttpServer.parseRange("bytes=0-99", 1000))
        assertEquals(RangeResult.Ok(500, 999), IsoHttpServer.parseRange("bytes=500-5000", 1000))
        assertEquals(RangeResult.Ok(900, 999), IsoHttpServer.parseRange("bytes=-100", 1000))
        assertEquals(RangeResult.Unsatisfiable, IsoHttpServer.parseRange("bytes=1000-", 1000))
        assertEquals(RangeResult.Unsatisfiable, IsoHttpServer.parseRange("bytes=5-4", 1000))
        assertNull(IsoHttpServer.parseRange("bytes=0-1,5-6", 1000))
        assertNull(IsoHttpServer.parseRange("items=0-1", 1000))
        assertNull(IsoHttpServer.parseRange("bytes=a-b", 1000))
    }

    @Test fun contentTypes() {
        assertEquals("application/vnd.efi.img", IsoHttpServer.contentType("x.IMG"))
        assertEquals("application/efi", IsoHttpServer.contentType("bootx64.efi"))
    }
}
