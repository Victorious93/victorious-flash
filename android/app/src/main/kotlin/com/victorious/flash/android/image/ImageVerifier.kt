package com.victorious.flash.android.image

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object ImageVerifier {
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    fun isSha256(s: String) = HEX64.matches(s.trim())

    /** Copies [input] to [output] while hashing; returns lowercase hex SHA-256 and byte count. */
    fun copyAndHash(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit = {}): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            output.write(buf, 0, n)
            total += n
            onProgress(total)
        }
        output.flush()
        return md.digest().joinToString("") { "%02x".format(it) } to total
    }

    /** GNU (`<hash>  [*]name`) and BSD (`SHA256 (name) = <hash>`) checksum files, as on desktop. */
    fun findSha256(contents: String, filename: String): String? {
        for (raw in contents.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("SHA256 (")) {
                val rest = line.removePrefix("SHA256 (")
                val i = rest.indexOf(") = ")
                if (i > 0 && rest.substring(0, i) == filename && isSha256(rest.substring(i + 4))) return rest.substring(i + 4).trim().lowercase()
                continue
            }
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[1].removePrefix("*").removePrefix("./")
                if (name == filename && isSha256(parts[0])) return parts[0].lowercase()
            }
        }
        return null
    }
}
