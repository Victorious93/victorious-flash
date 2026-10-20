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

/** Minimal key-value storage so the pin store is unit-testable without Android. */
interface Kv {
    fun getString(k: String): String?
    fun putString(k: String, v: String)
    fun getBoolean(k: String, d: Boolean): Boolean
    fun putBoolean(k: String, v: Boolean)
}

/** Controllers that ship pre-pinned. Pinning does NOT enable remote control: the switch stays off by default. */
object DefaultPins {
    const val DROIDCOMMAND_PACKAGE = "ai.droidcommand.app"
    /** DroidCommand debug signing cert. Replace with the release cert once it exists. */
    val DROIDCOMMAND_DEBUG_CERT = PinnedController.normalizeCert("88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D")!!
    const val DROIDCOMMAND_LABEL = "DroidCommand controller (debug cert)"
    val all = listOf(PinnedController(DROIDCOMMAND_PACKAGE, DROIDCOMMAND_DEBUG_CERT))
    fun labelFor(p: PinnedController): String? =
        if (p.packageName == DROIDCOMMAND_PACKAGE && p.normalizedCert == DROIDCOMMAND_DEBUG_CERT) DROIDCOMMAND_LABEL else null
}

/** Switch (default OFF) + pins. Default pins are seeded exactly once; a user removal is never undone. */
class PinStore(val kv: Kv) : RemoteSettings {
    init {
        if (!kv.getBoolean(SEEDED, false)) {
            val cur = PinnedController.listFromJson(kv.getString(CONTROLLERS))
            val add = DefaultPins.all.filter { d -> cur.none { it.packageName == d.packageName && it.normalizedCert == d.normalizedCert } }
            kv.putString(CONTROLLERS, PinnedController.listToJson(cur + add))
            kv.putBoolean(SEEDED, true)
        }
    }
    override val enabled get() = kv.getBoolean(ENABLED, false)
    override val controllers get() = PinnedController.listFromJson(kv.getString(CONTROLLERS))
    fun setEnabled(v: Boolean) = kv.putBoolean(ENABLED, v)
    fun setControllers(l: List<PinnedController>) = kv.putString(CONTROLLERS, PinnedController.listToJson(l))
    companion object { const val ENABLED = "enabled"; const val CONTROLLERS = "controllers"; const val SEEDED = "defaultPinsSeeded_v1" }
}
