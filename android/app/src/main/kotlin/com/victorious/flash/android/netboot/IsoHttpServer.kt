package com.victorious.flash.android.netboot

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Something the server can send at one fixed path. */
sealed class Resource {
    /** [length] bytes of [file] starting at [offset] (whole files, or files inside the ISO). */
    data class Slice(val file: File, val offset: Long, val length: Long, val contentType: String) : Resource()
    /** Generated per request; gets the server address the client connected to (e.g. "192.168.42.129:8080"). */
    class Generated(val contentType: String, val body: (hostPort: String) -> ByteArray) : Resource()
}

/**
 * Minimal read-only HTTP/1.1 server for UEFI HTTP Boot. Serves only the fixed [routes]
 * (GET/HEAD, single byte-range requests). No directory listing, no other paths, no uploads.
 * Pure JVM so it is unit-tested without Android.
 */
class IsoHttpServer(
    private val routes: Map<String, Resource>,
    private val bindAddresses: List<InetAddress>,
    private val port: Int = 0,
    private val log: (String) -> Unit = {},
) {
    /** Single-file convenience (the raw-ISO mode). */
    constructor(file: File, urlPath: String, bindAddresses: List<InetAddress>, port: Int = 0, log: (String) -> Unit = {}) :
        this(mapOf(urlPath to Resource.Slice(file, 0, file.length(), contentType(file.name))), bindAddresses, port, log)

    private val sockets = mutableListOf<ServerSocket>()
    private var pool: ExecutorService? = null
    val bytesServed = AtomicLong(0)
    @Volatile var boundPort: Int = -1; private set

    fun start() {
        require(routes.isNotEmpty() && routes.keys.all { it.startsWith("/") }) { "paths must start with /" }
        routes.values.filterIsInstance<Resource.Slice>().forEach { require(it.file.isFile) { "Image not found" } }
        val p = Executors.newCachedThreadPool { r -> Thread(r, "iso-http").apply { isDaemon = true } }
        pool = p
        var chosen = port
        try {
            for (addr in bindAddresses) {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(addr, chosen))
                if (chosen == 0) chosen = ss.localPort
                sockets += ss
                p.execute { acceptLoop(ss) }
            }
        } catch (e: IOException) { stop(); throw e }
        require(sockets.isNotEmpty()) { "No address to bind" }
        boundPort = chosen
    }

    fun stop() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool?.shutdownNow(); pool = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: SocketException) { return } catch (_: IOException) { continue }
            pool?.execute { handle(s) } ?: s.close()
        }
    }

    private fun handle(s: Socket) = s.use { sock ->
        sock.soTimeout = 60_000
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        // keep-alive loop: firmware HTTP stacks often reuse the connection
        while (true) {
            val req = readRequest(input) ?: return
            log("${sock.inetAddress.hostAddress} -> :${sock.localPort} ${req.method} ${req.target} host=${req.headers["host"] ?: "-"} ua=${req.headers["user-agent"] ?: "-"} ${req.headers["range"] ?: ""}")
            val keep = respond(req, out, hostPortFor(req.headers["host"], sock.localAddress.hostAddress!!, sock.localPort))
            out.flush()
            if (!keep) return
        }
    }

    data class Request(val method: String, val target: String, val headers: Map<String, String>)

    private fun readRequest(input: BufferedInputStream): Request? {
        val first = readLine(input) ?: return null
        if (first.isEmpty()) return null
        val parts = first.split(' ')
        if (parts.size < 3) return Request("BAD", "", emptyMap())
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (++count > 100) return Request("BAD", "", emptyMap())
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = try { input.read() } catch (_: IOException) { return null }
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    /** Returns whether the connection may be kept alive. */
    private fun respond(req: Request, out: OutputStream, hostPort: String): Boolean {
        val keep = !req.headers["connection"].equals("close", ignoreCase = true)
        if (req.method != "GET" && req.method != "HEAD") return simple(out, 405, "Method Not Allowed", false, "Allow: GET, HEAD\r\n", head = false)
        // shim builds the next-stage URL as "<dir>/" + "\\grubx64.efi" -> "/boot//grubx64.efi"
        // (seen in the QEMU/OVMF run), so collapse repeated slashes before the exact-match lookup.
        val path = req.target.substringBefore('?').replace(Regex("/{2,}"), "/")
        val res = routes[path] ?: return simple(out, 404, "Not Found", keep, head = req.method == "HEAD")
        val generated = (res as? Resource.Generated)?.body?.invoke(hostPort)
        val len = generated?.size?.toLong() ?: (res as Resource.Slice).length
        val type = (res as? Resource.Generated)?.contentType ?: (res as Resource.Slice).contentType
        val range = req.headers["range"]?.let { parseRange(it, len) }
        if (range == RangeResult.Unsatisfiable) return simple(out, 416, "Range Not Satisfiable", keep, "Content-Range: bytes */$len\r\n", head = req.method == "HEAD")
        val (start, end) = (range as? RangeResult.Ok)?.let { it.start to it.end } ?: (0L to len - 1)
        val n = if (len == 0L) 0 else end - start + 1
        val status = if (range is RangeResult.Ok) "206 Partial Content" else "200 OK"
        val h = StringBuilder("HTTP/1.1 $status\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: $n\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Cache-Control: no-store\r\n")
            .append(if (keep) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        if (range is RangeResult.Ok) h.append("Content-Range: bytes $start-$end/$len\r\n")
        h.append("\r\n")
        out.write(h.toString().toByteArray(Charsets.US_ASCII))
        if (req.method == "HEAD" || n == 0L) return keep
        if (generated != null) { out.write(generated, start.toInt(), n.toInt()); bytesServed.addAndGet(n); return keep }
        res as Resource.Slice
        RandomAccessFile(res.file, "r").use { raf ->
            raf.seek(res.offset + start)
            val buf = ByteArray(256 * 1024)
            var left = n
            while (left > 0) {
                val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) return false
                out.write(buf, 0, r)
                left -= r
                bytesServed.addAndGet(r.toLong())
            }
        }
        return keep
    }

    /** Error/short response. A HEAD reply must carry no body, or a keep-alive client (GRUB) misreads the next response. */
    private fun simple(out: OutputStream, code: Int, msg: String, keep: Boolean, extra: String = "", head: Boolean = false): Boolean {
        val body = if (head) ByteArray(0) else "$code $msg\n".toByteArray()
        out.write(("HTTP/1.1 $code $msg\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n$extra" +
            (if (keep) "Connection: keep-alive\r\n" else "Connection: close\r\n") + "\r\n").toByteArray(Charsets.US_ASCII))
        out.write(body)
        return keep
    }

    sealed class RangeResult {
        data class Ok(val start: Long, val end: Long) : RangeResult()
        object Unsatisfiable : RangeResult()
    }

    companion object {
        /**
         * Address the client used to reach us, for URLs we generate (grub.cfg iso-url). Prefer the IPv4
         * from the Host header (correct behind NAT, e.g. QEMU user-net), always with the real listening
         * port (clients may omit it). Falls back to the socket's local address.
         */
        fun hostPortFor(hostHeader: String?, localIp: String, localPort: Int): String {
            val ip = hostHeader?.trim()?.substringBefore(':')?.takeIf { h -> Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h) } ?: localIp
            return "$ip:$localPort"
        }

        /** EDK II HTTP Boot picks RAM-disk type from Content-Type (or extension). */
        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "iso" -> "application/vnd.efi.iso"
            "img" -> "application/vnd.efi.img"
            "efi" -> "application/efi"
            else -> "application/octet-stream"
        }

        /** Parses a single `bytes=` range. Multi-range or malformed headers are ignored (null => full 200). */
        fun parseRange(header: String, len: Long): RangeResult? {
            val h = header.trim()
            if (!h.startsWith("bytes=", ignoreCase = true)) return null
            val spec = h.substring(6).trim()
            if (spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return try {
                if (a.isEmpty()) {
                    val suffix = b.toLong()
                    if (suffix <= 0 || len == 0L) RangeResult.Unsatisfiable
                    else RangeResult.Ok(maxOf(0, len - suffix), len - 1)
                } else {
                    val start = a.toLong()
                    val end = if (b.isEmpty()) len - 1 else minOf(b.toLong(), len - 1)
                    if (start >= len || start < 0 || end < start) RangeResult.Unsatisfiable else RangeResult.Ok(start, end)
                }
            } catch (_: NumberFormatException) { null }
        }
    }
}
