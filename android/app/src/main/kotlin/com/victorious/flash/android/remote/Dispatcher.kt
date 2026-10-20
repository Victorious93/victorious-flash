package com.victorious.flash.android.remote

import org.json.JSONArray
import org.json.JSONObject

enum class Kind(val wire: String) { READ("read"), ACTION("action"), DESTRUCTIVE("destructive") }

data class ActionSpec(val name: String, val kind: Kind, val remoteAllowed: Boolean = true) {
    /** Destructive actions always need on-phone confirmation; this is not configurable. */
    val requiresConfirm get() = kind == Kind.DESTRUCTIVE
}

object ActionRegistry {
    const val STATUS_GET = "status.get"
    const val CAPABILITIES_GET = "capabilities.get"
    const val SERVE_START = "netboot.serve.start"
    const val SERVE_STOP = "netboot.serve.stop"
    const val OTG_WRITE = "otg.write"
    const val GADGET_EXPOSE = "gadget.expose"

    val all = listOf(
        ActionSpec(STATUS_GET, Kind.READ), ActionSpec(CAPABILITIES_GET, Kind.READ),
        ActionSpec(SERVE_START, Kind.ACTION), ActionSpec(SERVE_STOP, Kind.ACTION),
        ActionSpec(OTG_WRITE, Kind.DESTRUCTIVE), ActionSpec(GADGET_EXPOSE, Kind.DESTRUCTIVE),
    )
    fun of(name: String) = all.firstOrNull { it.name == name }
}

/** The app features the bridge may touch. Implemented by AndroidNodeActions; faked in tests. */
interface NodeActions {
    val nodeRef: String
    fun status(): JSONObject
    /** Null if available, otherwise why the capability is off (e.g. no root, no verified image). */
    fun unavailableReason(actionRef: String): String?
    fun serveStart(params: JSONObject): JSONObject
    fun serveStop(params: JSONObject): JSONObject
    /** Validates params, then posts the on-phone danger confirmation. MUST NOT perform the action. Returns a confirmation id. */
    fun requestConfirmation(actionRef: String, params: JSONObject, requestId: String, controller: PinnedController): String
}

interface RemoteSettings {
    val enabled: Boolean
    val controllers: List<PinnedController>
}

/**
 * Order of checks: feature switch -> OS caller identity vs pins -> envelope parse -> target -> freshness -> nonce -> dispatch.
 * Destructive actions can only ever yield confirmation_required; they run after Don confirms on the phone.
 */
class RemoteDispatcher(private val settings: RemoteSettings, private val actions: NodeActions, private val nonces: NonceCache, private val clock: () -> Long) {

    fun handle(raw: String, caller: CallerIdentity): String = handleJson(raw, caller).toString()

    fun handleJson(raw: String, caller: CallerIdentity): JSONObject {
        val reqId = runCatching { JSONObject(raw).optString("requestId", "") }.getOrDefault("")
        return try {
            if (!settings.enabled) throw RemoteError(ErrorCode.EXTERNAL_CONTROL_DISABLED, "fleet remote control is off on this phone")
            val controller = Authorizer.authorize(caller, settings.controllers)
            val env = EnvelopeParser.parse(raw)
            if (env.targetNodeRef != null && env.targetNodeRef != actions.nodeRef) throw RemoteError(ErrorCode.NOT_FOUND, "targetNodeRef does not match this node")
            Freshness.check(env, clock())
            if (!nonces.checkAndRecord(controller.sourceNodeRef, env.nonce)) throw RemoteError(ErrorCode.REPLAY_DETECTED, "nonce already used")
            ok(env.requestId, dispatch(env, controller))
        } catch (e: RemoteError) {
            err(reqId, e)
        } catch (e: Exception) {
            err(reqId, RemoteError(ErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun dispatch(env: Envelope, c: PinnedController): JSONObject = when (env.op) {
        Op.CAPABILITIES_GET -> capabilities()
        Op.REMOTE_COMMAND -> runAction(env.actionRef!!, env.params, env.requestId, c)
        Op.MACRO_STEP -> runAction(env.actionRef!!, env.params, env.requestId, c)
            .put("macroId", env.macroId).put("stepIndex", env.stepIndex).put("onError", env.onError!!.wire)
        Op.MACRO_RUN -> macroRun(env, c)
    }

    fun capabilities(): JSONObject {
        val ops = JSONArray()
        ActionRegistry.all.forEach { a ->
            ops.put(JSONObject().put("name", a.name).put("kind", a.kind.wire).put("remoteAllowed", a.remoteAllowed && settings.enabled)
                .put("requiresConfirm", a.requiresConfirm).also { o -> actions.unavailableReason(a.name)?.let { o.put("available", false).put("unavailableReason", it) } ?: o.put("available", true) })
        }
        return JSONObject().put("protocol", proto()).put("nodeRef", actions.nodeRef).put("binding", "EXTERNAL_NODE")
            .put("bridgeOps", JSONArray(Op.values().map { it.wire })).put("ops", ops)
    }

    private fun runAction(ref: String, params: JSONObject, requestId: String, c: PinnedController): JSONObject {
        val spec = ActionRegistry.of(ref) ?: throw RemoteError(ErrorCode.NOT_FOUND, "unknown actionRef '$ref'")
        if (!spec.remoteAllowed) throw RemoteError(ErrorCode.CAPABILITY_OFF, "$ref is not remotely allowed")
        actions.unavailableReason(ref)?.let { throw RemoteError(ErrorCode.CAPABILITY_OFF, it) }
        return when (spec.kind) {
            Kind.DESTRUCTIVE -> {
                // Never executed here. The envelope's `destructive`/`trigger` fields cannot change this.
                val id = actions.requestConfirmation(ref, params, requestId, c)
                throw RemoteError(ErrorCode.CONFIRMATION_REQUIRED, "Confirm on the phone: a red danger screen has been posted", JSONObject().put("confirmationId", id).put("actionRef", ref))
            }
            Kind.READ -> when (ref) { ActionRegistry.CAPABILITIES_GET -> capabilities(); else -> actions.status() }.let { JSONObject().put("actionRef", ref).put("data", it) }
            Kind.ACTION -> when (ref) {
                ActionRegistry.SERVE_START -> actions.serveStart(params)
                ActionRegistry.SERVE_STOP -> actions.serveStop(params)
                else -> throw RemoteError(ErrorCode.NOT_FOUND, "unknown actionRef '$ref'")
            }.let { JSONObject().put("actionRef", ref).put("data", it) }
        }
    }

    private fun macroRun(env: Envelope, c: PinnedController): JSONObject {
        val results = JSONArray()
        val out = JSONObject().put("macroId", env.macroId).put("dryRun", env.dryRun).put("steps", results)
        if (env.dryRun) {
            env.steps.forEachIndexed { i, s ->
                val spec = ActionRegistry.of(s.actionRef)
                results.put(JSONObject().put("stepIndex", i).put("actionRef", s.actionRef).put("known", spec != null)
                    .put("kind", spec?.kind?.wire ?: JSONObject.NULL).put("requiresConfirm", spec?.requiresConfirm ?: false)
                    .put("unavailableReason", spec?.let { actions.unavailableReason(it.name) } ?: JSONObject.NULL))
            }
            return out.put("completed", false)
        }
        for ((i, s) in env.steps.withIndex()) {
            try {
                results.put(JSONObject().put("stepIndex", i).put("ok", true).put("result", runAction(s.actionRef, s.params, "${env.requestId}#$i", c)))
            } catch (e: RemoteError) {
                results.put(JSONObject().put("stepIndex", i).put("ok", false).put("error", errObj(e)))
                // A destructive step always halts the macro: nothing after it may run before Don confirms.
                if (e.code == ErrorCode.CONFIRMATION_REQUIRED) throw RemoteError(e.code, "macro paused at step $i: " + e.message, (e.data ?: JSONObject()).put("stepIndex", i).put("steps", results))
                if (s.onError == OnError.ABORT) return out.put("completed", false).put("abortedAt", i)
            }
        }
        return out.put("completed", true)
    }

    companion object {
        fun proto() = JSONObject().put("major", BridgeProtocol.MAJOR).put("minor", BridgeProtocol.MINOR)
        fun ok(id: String, r: JSONObject) = JSONObject().put("protocol", proto()).put("requestId", id).put("ok", true).put("result", r)
        fun errObj(e: RemoteError) = JSONObject().put("code", e.code.wire).put("message", e.message ?: "").also { o -> e.data?.let { o.put("data", it) } }
        fun err(id: String, e: RemoteError) = JSONObject().put("protocol", proto()).put("requestId", id).put("ok", false).put("error", errObj(e))
    }
}
