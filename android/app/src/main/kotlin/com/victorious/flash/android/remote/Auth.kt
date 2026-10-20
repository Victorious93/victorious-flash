package com.victorious.flash.android.remote

import org.json.JSONArray
import org.json.JSONObject

/** One allow-listed controller: an Android package plus the SHA-256 of its signing certificate. */
data class PinnedController(val packageName: String, val certSha256: String) {
    val normalizedCert get() = normalizeCert(certSha256)
    val isComplete get() = PKG.matches(packageName) && normalizedCert != null
    /** Authenticated identity used as sourceNodeRef for replay keys. */
    val sourceNodeRef get() = "$packageName:${normalizedCert ?: ""}"

    companion object {
        val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        fun normalizeCert(s: String): String? = s.replace(":", "").replace(" ", "").lowercase().takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
        fun listToJson(l: List<PinnedController>) = JSONArray(l.map { JSONObject().put("package", it.packageName).put("certSha256", it.certSha256) }).toString()
        fun listFromJson(s: String?): List<PinnedController> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { a.getJSONObject(it).let { o -> PinnedController(o.getString("package"), o.optString("certSha256", "")) } }
        }.getOrDefault(emptyList())
    }
}

/** Who is calling, as reported by the OS (calling UID -> packages -> signing certs). Nothing here comes from the envelope. */
data class CallerIdentity(val uid: Int, val packages: Map<String, List<String>>)

object Authorizer {
    /**
     * Returns the pinned controller matching the OS-reported caller, or throws.
     * Only complete pins (valid package + 64-hex cert) count; an empty cert never matches.
     */
    fun authorize(caller: CallerIdentity, allowlist: List<PinnedController>): PinnedController {
        val pins = allowlist.filter { it.isComplete }
        if (pins.isEmpty()) throw RemoteError(ErrorCode.NOT_PAIRED, "no controller is pinned on this phone")
        for ((pkg, certs) in caller.packages) {
            val callerCerts = certs.mapNotNull(PinnedController::normalizeCert).toSet()
            if (callerCerts.isEmpty()) continue
            pins.firstOrNull { it.packageName == pkg && it.normalizedCert in callerCerts }?.let { return it }
        }
        throw RemoteError(ErrorCode.UNAUTHORIZED, "caller is not an allow-listed controller")
    }
}
