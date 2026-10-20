package com.victorious.flash.android.remote

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Fleet Orchestrator bridge protocol, frozen spec. */
object BridgeProtocol {
    const val MAJOR = 1
    const val MINOR = 1
    const val DEFAULT_TTL_MS = 120_000L
    const val MAX_TTL_MS = 300_000L
    const val MAX_SKEW_MS = 120_000L
    const val NONCE_RETENTION_MS = 10 * 60_000L
    const val MAX_MACRO_STEPS = 32
}

enum class ErrorCode(val wire: String) {
    INVALID_PARAMETERS("invalid_parameters"), NOT_PAIRED("not_paired"), NOT_FOUND("not_found"), INTERNAL("internal"),
    UNAUTHORIZED("unauthorized"), REPLAY_DETECTED("replay_detected"), EXPIRED("expired"),
    EXTERNAL_CONTROL_DISABLED("external_control_disabled"), CONFIRMATION_REQUIRED("confirmation_required"),
    UNSUPPORTED_OP("unsupported_op"), CAPABILITY_OFF("capability_off"),
}

class RemoteError(val code: ErrorCode, message: String, val data: JSONObject? = null) : Exception(message)

enum class Op(val wire: String) {
    CAPABILITIES_GET("capabilities.get"), REMOTE_COMMAND("remote.command"), MACRO_STEP("macro.step"), MACRO_RUN("macro.run");
    companion object { fun of(s: String) = values().firstOrNull { it.wire == s } }
}

enum class OnError(val wire: String) { ABORT("abort"), CONTINUE("continue");
    companion object { fun of(s: String) = values().firstOrNull { it.wire == s } }
}

data class MacroStepSpec(val actionRef: String, val params: JSONObject, val onError: OnError)

data class Envelope(
    val op: Op, val requestId: String, val targetNodeRef: String?, val actionRef: String?, val params: JSONObject,
    val nonce: String, val timestampMs: Long, val ttlMs: Long, val destructive: Boolean, val trigger: JSONObject?,
    val macroId: String? = null, val stepIndex: Int? = null, val onError: OnError? = null,
    val steps: List<MacroStepSpec> = emptyList(), val dryRun: Boolean = false,
)

/** Strict parser. Unknown ops -> unsupported_op; malformed fields -> invalid_parameters. Never used for authorization. */
object EnvelopeParser {
    private val ID = Regex("^[A-Za-z0-9._:\\-]{1,128}$")

    fun parse(raw: String): Envelope {
        val o = try { JSONObject(raw) } catch (e: Exception) { throw bad("envelope is not a JSON object") }
        val requestId = str(o, "requestId", required = true)!!
        val opS = str(o, "op", required = true)!!
        val op = Op.of(opS) ?: throw RemoteError(ErrorCode.UNSUPPORTED_OP, "unsupported op '$opS'")
        val nonce = str(o, "nonce", required = true)!!
        if (!isNonce128(nonce)) throw bad("nonce must be 128 bits (32 hex chars or base64 of 16 bytes)")
        if (!o.has("timestampMs") || o.opt("timestampMs") !is Number) throw bad("timestampMs must be a number")
        val ts = o.getLong("timestampMs")
        val ttl = if (o.has("ttlMs") && !o.isNull("ttlMs")) (o.opt("ttlMs") as? Number)?.toLong() ?: throw bad("ttlMs must be a number") else BridgeProtocol.DEFAULT_TTL_MS
        if (ttl <= 0 || ttl > BridgeProtocol.MAX_TTL_MS) throw bad("ttlMs must be 1..${BridgeProtocol.MAX_TTL_MS}")
        val params = when (val p = o.opt("params")) { null, JSONObject.NULL -> JSONObject(); is JSONObject -> p; else -> throw bad("params must be an object") }
        val trigger = when (val t = o.opt("trigger")) { null, JSONObject.NULL -> null; is JSONObject -> t; else -> throw bad("trigger must be an object") }
        val destructive = when (val d = o.opt("destructive")) { null, JSONObject.NULL -> false; is Boolean -> d; else -> throw bad("destructive must be a boolean") }
        val target = str(o, "targetNodeRef", required = false)
        val actionRef = str(o, "actionRef", required = op == Op.REMOTE_COMMAND || op == Op.MACRO_STEP)
        var env = Envelope(op, requestId, target, actionRef, params, nonce, ts, ttl, destructive, trigger)
        when (op) {
            Op.MACRO_STEP -> {
                val idx = (o.opt("stepIndex") as? Number)?.toInt()?.takeIf { it >= 0 } ?: throw bad("stepIndex must be a non-negative integer")
                val oe = OnError.of(str(o, "onError", required = true)!!) ?: throw bad("onError must be abort|continue")
                env = env.copy(macroId = str(o, "macroId", required = true), stepIndex = idx, onError = oe)
            }
            Op.MACRO_RUN -> {
                val arr = o.opt("steps") as? JSONArray ?: throw bad("steps must be an array")
                if (arr.length() == 0 || arr.length() > BridgeProtocol.MAX_MACRO_STEPS) throw bad("steps must have 1..${BridgeProtocol.MAX_MACRO_STEPS} entries")
                val steps = (0 until arr.length()).map { i ->
                    val s = arr.opt(i) as? JSONObject ?: throw bad("steps[$i] must be an object")
                    val p = when (val x = s.opt("params")) { null, JSONObject.NULL -> JSONObject(); is JSONObject -> x; else -> throw bad("steps[$i].params must be an object") }
                    val oe = s.optString("onError", "abort").let { OnError.of(it) ?: throw bad("steps[$i].onError must be abort|continue") }
                    MacroStepSpec(str(s, "actionRef", required = true)!!, p, oe)
                }
                val dry = when (val d = o.opt("dryRun")) { null, JSONObject.NULL -> false; is Boolean -> d; else -> throw bad("dryRun must be a boolean") }
                env = env.copy(macroId = str(o, "macroId", required = true), steps = steps, dryRun = dry)
            }
            else -> {}
        }
        return env
    }

    fun isNonce128(n: String): Boolean {
        if (Regex("^[0-9a-fA-F]{32}$").matches(n)) return true
        return runCatching {
            val b = if (n.contains('-') || n.contains('_')) Base64.getUrlDecoder().decode(n) else Base64.getDecoder().decode(n)
            b.size == 16
        }.getOrDefault(false)
    }

    private fun str(o: JSONObject, k: String, required: Boolean): String? {
        val v = o.opt(k)
        if (v == null || v == JSONObject.NULL) { if (required) throw bad("missing $k"); return null }
        if (v !is String || !ID.matches(v)) throw bad("$k must be a 1..128 char id")
        return v
    }

    private fun bad(m: String) = RemoteError(ErrorCode.INVALID_PARAMETERS, m)
}

/** Expiry and clock-skew check. */
object Freshness {
    fun check(e: Envelope, now: Long) {
        if (now > e.timestampMs + e.ttlMs) throw RemoteError(ErrorCode.EXPIRED, "request expired")
        if (kotlin.math.abs(now - e.timestampMs) > BridgeProtocol.MAX_SKEW_MS) throw RemoteError(ErrorCode.EXPIRED, "clock skew over ${BridgeProtocol.MAX_SKEW_MS} ms")
    }
}

/** Duplicate-nonce detector keyed on (sourceNodeRef, nonce), retaining entries at least 10 minutes. */
class NonceCache(private val retentionMs: Long = BridgeProtocol.NONCE_RETENTION_MS, private val maxEntries: Int = 100_000, private val clock: () -> Long) {
    init { require(retentionMs >= BridgeProtocol.NONCE_RETENTION_MS) }
    private val seen = LinkedHashMap<Pair<String, String>, Long>()

    /** Returns false if this (source, nonce) was already seen within retention; otherwise records it. */
    @Synchronized fun checkAndRecord(source: String, nonce: String): Boolean {
        val now = clock()
        val it = seen.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (now - e.value >= retentionMs) it.remove() else break } // insertion order == time order
        val key = source to nonce.lowercase()
        if (seen.containsKey(key)) return false
        if (seen.size >= maxEntries) throw RemoteError(ErrorCode.INTERNAL, "replay cache full; retry later")
        seen[key] = now
        return true
    }

    @Synchronized fun size() = seen.size
}
