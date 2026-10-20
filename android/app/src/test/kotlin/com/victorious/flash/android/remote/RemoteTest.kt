package com.victorious.flash.android.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private const val CERT = "2c520f7aa4be726debf9183d043d56c558e567f777bd10be3c9886381c6af6ed"
private const val PKG = "com.fleet.orchestrator"
private const val NODE = "vfa-test"

class FakeActions : NodeActions {
    override val nodeRef = NODE
    val calls = mutableListOf<String>()
    val confirmations = mutableListOf<Pair<String, JSONObject>>()
    var off = mutableMapOf<String, String>()
    var executedDestructive = 0
    override fun status() = JSONObject().put("ok", true).also { calls += "status" }
    override fun unavailableReason(actionRef: String) = off[actionRef]
    override fun serveStart(params: JSONObject) = JSONObject().put("started", true).also { calls += "serveStart" }
    override fun serveStop(params: JSONObject) = JSONObject().put("stopping", true).also { calls += "serveStop" }
    override fun requestConfirmation(actionRef: String, params: JSONObject, requestId: String, controller: PinnedController): String {
        confirmations += actionRef to params; return "conf-${confirmations.size}"
    }
}

class FakeSettings(override var enabled: Boolean = true, override var controllers: List<PinnedController> = listOf(PinnedController(PKG, CERT))) : RemoteSettings

class RemoteTest {
    private var now = 1_800_000_000_000L
    private var n = 0
    private val goodCaller = CallerIdentity(10123, mapOf(PKG to listOf(CERT.uppercase().chunked(2).joinToString(":"))))

    private fun nonce() = "%032x".format(++n)
    private fun env(op: String = "remote.command", action: String? = "status.get", extra: JSONObject.() -> Unit = {}) = JSONObject()
        .put("op", op).put("requestId", "req-$n").put("targetNodeRef", NODE).also { if (action != null) it.put("actionRef", action) }
        .put("params", JSONObject()).put("nonce", nonce()).put("timestampMs", now).put("ttlMs", 120000).put("destructive", false).apply(extra)

    private fun setup(settings: FakeSettings = FakeSettings(), actions: FakeActions = FakeActions()) =
        Triple(RemoteDispatcher(settings, actions, NonceCache(clock = { now }), { now }), actions, settings)

    private fun JSONObject.code() = optJSONObject("error")?.getString("code")

    // ---------- parsing ----------
    @Test fun parsesFullEnvelope() {
        val e = EnvelopeParser.parse(env { put("trigger", JSONObject().put("source", "cron")) }.toString())
        assertEquals(Op.REMOTE_COMMAND, e.op); assertEquals("status.get", e.actionRef); assertEquals(120000L, e.ttlMs); assertNotNull(e.trigger)
    }
    @Test fun defaultTtl() { assertEquals(120000L, EnvelopeParser.parse(env { remove("ttlMs") }.toString()).ttlMs) }
    @Test fun ttlBounds() {
        for (t in listOf(0, -1, 300001)) assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { put("ttlMs", t) }.toString()) }
        assertEquals(300000L, EnvelopeParser.parse(env { put("ttlMs", 300000) }.toString()).ttlMs)
    }
    @Test fun rejectsBadFields() {
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse("not json") }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { remove("requestId") }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { put("nonce", "abc") }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { put("nonce", "%030x".format(1)) }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { put("timestampMs", "now") }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { put("params", JSONArray()) }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { put("destructive", "yes") }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env { put("trigger", "x") }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env(action = null).toString()) }
    }
    @Test fun unknownOp() { assertErr(ErrorCode.UNSUPPORTED_OP) { EnvelopeParser.parse(env(op = "shell.exec").toString()) } }
    @Test fun nonceFormats() {
        assertTrue(EnvelopeParser.isNonce128("00112233445566778899aabbccddeeff"))
        assertTrue(EnvelopeParser.isNonce128(java.util.Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })))
        assertTrue(EnvelopeParser.isNonce128(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { (it * 17).toByte() })))
        assertFalse(EnvelopeParser.isNonce128(java.util.Base64.getEncoder().encodeToString(ByteArray(8))))
    }
    @Test fun macroStepParsing() {
        val e = EnvelopeParser.parse(env("macro.step") { put("macroId", "m1"); put("stepIndex", 2); put("onError", "continue") }.toString())
        assertEquals(2, e.stepIndex); assertEquals(OnError.CONTINUE, e.onError)
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env("macro.step") { put("macroId", "m1"); put("stepIndex", 0); put("onError", "retry") }.toString()) }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env("macro.step") { put("macroId", "m1"); put("stepIndex", -1); put("onError", "abort") }.toString()) }
    }
    @Test fun macroRunParsing() {
        val steps = JSONArray().put(JSONObject().put("actionRef", "status.get"))
        val e = EnvelopeParser.parse(env("macro.run", null) { put("macroId", "m"); put("steps", steps); put("dryRun", true) }.toString())
        assertTrue(e.dryRun); assertEquals(1, e.steps.size); assertEquals(OnError.ABORT, e.steps[0].onError)
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env("macro.run", null) { put("macroId", "m"); put("steps", JSONArray()) }.toString()) }
        val many = JSONArray().apply { repeat(33) { put(JSONObject().put("actionRef", "status.get")) } }
        assertErr(ErrorCode.INVALID_PARAMETERS) { EnvelopeParser.parse(env("macro.run", null) { put("macroId", "m"); put("steps", many) }.toString()) }
    }

    // ---------- ttl / skew ----------
    @Test fun freshness() {
        val e = EnvelopeParser.parse(env { put("ttlMs", 60000) }.toString())
        Freshness.check(e, now); Freshness.check(e, now + 60000)
        assertErr(ErrorCode.EXPIRED) { Freshness.check(e, now + 60001) }
        val long = EnvelopeParser.parse(env { put("ttlMs", 300000) }.toString())
        Freshness.check(long, now + 120000)
        assertErr(ErrorCode.EXPIRED) { Freshness.check(long, now + 120001) } // skew limit beats ttl
        assertErr(ErrorCode.EXPIRED) { Freshness.check(long, now - 120001) } // from the future
        Freshness.check(long, now - 120000)
    }
    @Test fun expiredViaDispatcher() {
        val (d, a) = setup()
        val r = d.handleJson(env { put("timestampMs", now - 200000) }.toString(), goodCaller)
        assertEquals("expired", r.code()); assertTrue(a.calls.isEmpty())
    }

    // ---------- nonce cache ----------
    @Test fun nonceCacheKeyedOnSourceAndRetainedTenMinutes() {
        var t = 0L; val c = NonceCache(clock = { t })
        assertTrue(c.checkAndRecord("a", "N")); assertFalse(c.checkAndRecord("a", "n")); assertTrue(c.checkAndRecord("b", "N"))
        t = 10 * 60_000L - 1; assertFalse(c.checkAndRecord("a", "N"))
        t = 10 * 60_000L; assertTrue(c.checkAndRecord("a", "N"))
    }
    @Test(expected = IllegalArgumentException::class) fun nonceRetentionAtLeastTenMinutes() { NonceCache(retentionMs = 60_000, clock = { 0 }) }
    @Test fun nonceCacheBounded() {
        val c = NonceCache(maxEntries = 2, clock = { 0 }); c.checkAndRecord("a", "1"); c.checkAndRecord("a", "2")
        assertErr(ErrorCode.INTERNAL) { c.checkAndRecord("a", "3") }
    }
    @Test fun replayRejected() {
        val (d, a) = setup()
        val e = env().toString()
        assertEquals(true, d.handleJson(e, goodCaller).getBoolean("ok"))
        assertEquals("replay_detected", d.handleJson(e, goodCaller).code())
        assertEquals(1, a.calls.size)
    }
    @Test fun invalidRequestDoesNotBurnNonce() {
        val (d) = setup()
        val e = env { put("targetNodeRef", "other-node") }
        assertEquals("not_found", d.handleJson(e.toString(), goodCaller).code())
        assertEquals(true, d.handleJson(e.put("targetNodeRef", NODE).toString(), goodCaller).getBoolean("ok"))
    }

    // ---------- auth ----------
    @Test fun authAcceptsPinnedPackageAndCert() { assertEquals(PKG, Authorizer.authorize(goodCaller, FakeSettings().controllers).packageName) }
    @Test fun authRejections() {
        val pins = FakeSettings().controllers
        assertErr(ErrorCode.UNAUTHORIZED) { Authorizer.authorize(CallerIdentity(1, mapOf("com.evil" to listOf(CERT))), pins) }
        assertErr(ErrorCode.UNAUTHORIZED) { Authorizer.authorize(CallerIdentity(1, mapOf(PKG to listOf("ab".repeat(32)))), pins) }
        assertErr(ErrorCode.UNAUTHORIZED) { Authorizer.authorize(CallerIdentity(1, mapOf(PKG to emptyList())), pins) }
        assertErr(ErrorCode.UNAUTHORIZED) { Authorizer.authorize(CallerIdentity(1, emptyMap()), pins) }
        assertErr(ErrorCode.NOT_PAIRED) { Authorizer.authorize(goodCaller, emptyList()) }
        assertErr(ErrorCode.NOT_PAIRED) { Authorizer.authorize(goodCaller, listOf(PinnedController(PKG, ""))) } // empty cert never matches
    }
    @Test fun envelopeAndTriggerCannotAuthorize() {
        val (d, a) = setup()
        val evil = CallerIdentity(9, mapOf("com.evil" to listOf("00".repeat(32))))
        val e = env { put("trigger", JSONObject().put("package", PKG).put("certSha256", CERT).put("authorized", true)); put("sourceNodeRef", "$PKG:$CERT") }
        assertEquals("unauthorized", d.handleJson(e.toString(), evil).code())
        assertTrue(a.calls.isEmpty())
    }
    @Test fun pinsRoundTripAndNormalize() {
        val l = listOf(PinnedController(PKG, CERT.uppercase().chunked(2).joinToString(":")), PinnedController("com.x.y", ""))
        val back = PinnedController.listFromJson(PinnedController.listToJson(l))
        assertEquals(l, back); assertEquals(CERT, back[0].normalizedCert); assertFalse(back[1].isComplete)
        assertTrue(PinnedController.listFromJson("garbage").isEmpty())
    }

    // ---------- off by default ----------
    @Test fun offByDefaultRefusesEverything() {
        val (d, a) = setup(FakeSettings(enabled = false))
        for (op in listOf(env(), env("capabilities.get", null), env(action = "otg.write"))) {
            assertEquals("external_control_disabled", d.handleJson(op.toString(), goodCaller).code())
        }
        assertTrue(a.calls.isEmpty() && a.confirmations.isEmpty())
    }
    @Test fun enabledWithoutPinsIsNotPaired() {
        val (d) = setup(FakeSettings(controllers = emptyList()))
        assertEquals("not_paired", d.handleJson(env().toString(), goodCaller).code())
    }

    // ---------- capabilities ----------
    @Test fun capabilitiesListKinds() {
        val (d, a) = setup(); a.off[ActionRegistry.GADGET_EXPOSE] = "no root"
        val r = d.handleJson(env("capabilities.get", null).toString(), goodCaller).getJSONObject("result")
        assertEquals(1, r.getJSONObject("protocol").getInt("major")); assertEquals(1, r.getJSONObject("protocol").getInt("minor"))
        val ops = r.getJSONArray("ops"); val byName = (0 until ops.length()).associate { ops.getJSONObject(it).let { o -> o.getString("name") to o } }
        assertEquals("read", byName["status.get"]!!.getString("kind"))
        assertEquals("action", byName["netboot.serve.start"]!!.getString("kind"))
        for (x in listOf("otg.write", "gadget.expose")) { assertEquals("destructive", byName[x]!!.getString("kind")); assertTrue(byName[x]!!.getBoolean("requiresConfirm")) }
        assertFalse(byName["status.get"]!!.getBoolean("requiresConfirm"))
        assertTrue(byName.values.all { it.has("remoteAllowed") })
        assertFalse(byName["gadget.expose"]!!.getBoolean("available"))
    }

    // ---------- actions ----------
    @Test fun readAndActionRun() {
        val (d, a) = setup()
        assertTrue(d.handleJson(env().toString(), goodCaller).getBoolean("ok"))
        assertTrue(d.handleJson(env(action = "netboot.serve.start").toString(), goodCaller).getBoolean("ok"))
        assertTrue(d.handleJson(env(action = "netboot.serve.stop").toString(), goodCaller).getBoolean("ok"))
        assertEquals(listOf("status", "serveStart", "serveStop"), a.calls)
    }
    @Test fun unknownActionAndCapabilityOff() {
        val (d, a) = setup(); a.off[ActionRegistry.SERVE_START] = "no verified image"
        assertEquals("not_found", d.handleJson(env(action = "disk.format").toString(), goodCaller).code())
        assertEquals("capability_off", d.handleJson(env(action = "netboot.serve.start").toString(), goodCaller).code())
        assertTrue(a.calls.isEmpty())
    }

    // ---------- destructive gate ----------
    @Test fun destructiveAlwaysNeedsConfirmation() {
        val (d, a) = setup()
        for (ref in listOf("otg.write", "gadget.expose")) for (adv in listOf(true, false)) {
            val r = d.handleJson(env(action = ref) { put("destructive", adv); put("trigger", JSONObject().put("autoApprove", true).put("confirmed", true)); put("params", JSONObject().put("deviceName", "/dev/bus/usb/001/002").put("confirm", "ERASE")) }.toString(), goodCaller)
            assertFalse(r.getBoolean("ok")); assertEquals("confirmation_required", r.code())
            assertTrue(r.getJSONObject("error").getJSONObject("data").getString("confirmationId").startsWith("conf-"))
        }
        assertEquals(4, a.confirmations.size); assertEquals(0, a.executedDestructive); assertTrue(a.calls.isEmpty())
    }
    @Test fun macroStepDestructiveGated() {
        val (d, a) = setup()
        val r = d.handleJson(env("macro.step", "otg.write") { put("macroId", "m"); put("stepIndex", 0); put("onError", "continue") }.toString(), goodCaller)
        assertEquals("confirmation_required", r.code()); assertEquals(1, a.confirmations.size)
        val ok = d.handleJson(env("macro.step", "status.get") { put("macroId", "m"); put("stepIndex", 1); put("onError", "abort") }.toString(), goodCaller)
        assertEquals(1, ok.getJSONObject("result").getInt("stepIndex"))
    }
    @Test fun macroRunHaltsAtDestructiveStep() {
        val (d, a) = setup()
        val steps = JSONArray().put(JSONObject().put("actionRef", "status.get")).put(JSONObject().put("actionRef", "otg.write").put("onError", "continue"))
            .put(JSONObject().put("actionRef", "netboot.serve.start"))
        val r = d.handleJson(env("macro.run", null) { put("macroId", "m"); put("steps", steps) }.toString(), goodCaller)
        assertEquals("confirmation_required", r.code()); assertEquals(1, r.getJSONObject("error").getJSONObject("data").getInt("stepIndex"))
        assertEquals(listOf("status"), a.calls) // step 2 never ran
    }
    @Test fun macroRunDryRunExecutesNothing() {
        val (d, a) = setup()
        val steps = JSONArray().put(JSONObject().put("actionRef", "otg.write")).put(JSONObject().put("actionRef", "netboot.serve.start")).put(JSONObject().put("actionRef", "nope"))
        val r = d.handleJson(env("macro.run", null) { put("macroId", "m"); put("steps", steps); put("dryRun", true) }.toString(), goodCaller).getJSONObject("result")
        val s = r.getJSONArray("steps")
        assertTrue(s.getJSONObject(0).getBoolean("requiresConfirm")); assertFalse(s.getJSONObject(2).getBoolean("known"))
        assertTrue(a.calls.isEmpty() && a.confirmations.isEmpty())
    }
    @Test fun macroRunOnErrorSemantics() {
        val (d, a) = setup(); a.off[ActionRegistry.SERVE_START] = "off"
        val cont = JSONArray().put(JSONObject().put("actionRef", "netboot.serve.start").put("onError", "continue")).put(JSONObject().put("actionRef", "status.get"))
        val r1 = d.handleJson(env("macro.run", null) { put("macroId", "m"); put("steps", cont) }.toString(), goodCaller).getJSONObject("result")
        assertTrue(r1.getBoolean("completed")); assertEquals(listOf("status"), a.calls)
        val abort = JSONArray().put(JSONObject().put("actionRef", "netboot.serve.start")).put(JSONObject().put("actionRef", "status.get"))
        val r2 = d.handleJson(env("macro.run", null) { put("macroId", "m"); put("steps", abort) }.toString(), goodCaller).getJSONObject("result")
        assertFalse(r2.getBoolean("completed")); assertEquals(0, r2.getInt("abortedAt")); assertEquals(1, a.calls.size)
    }
    @Test fun responseEnvelope() {
        val (d) = setup()
        val r = d.handleJson(env { put("requestId", "abc-1") }.toString(), goodCaller)
        assertEquals("abc-1", r.getString("requestId")); assertEquals(1, r.getJSONObject("protocol").getInt("major"))
        val bad = d.handleJson("{]", goodCaller); assertEquals("invalid_parameters", bad.code())
    }

    private fun assertErr(code: ErrorCode, f: () -> Unit) {
        try { f(); fail("expected ${code.wire}") } catch (e: RemoteError) { assertEquals(code, e.code) }
    }
}
