package com.victorious.flash.android.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MapKv : Kv {
    val m = mutableMapOf<String, Any>()
    override fun getString(k: String) = m[k] as String?
    override fun putString(k: String, v: String) { m[k] = v }
    override fun getBoolean(k: String, d: Boolean) = m[k] as Boolean? ?: d
    override fun putBoolean(k: String, v: Boolean) { m[k] = v }
}

class DefaultPinTest {
    private val cert = "880051a6d95d38dd8d81e1931e1705480387d593c30943539968e6ef11a0c84d"

    @Test fun defaultPinPresentAndLabelled() {
        val s = PinStore(MapKv())
        assertEquals(listOf(PinnedController("ai.droidcommand.app", cert)), s.controllers)
        assertTrue(s.controllers[0].isComplete)
        assertEquals("DroidCommand controller (debug cert)", DefaultPins.labelFor(s.controllers[0]))
    }
    @Test fun switchOffByDefault() { assertFalse(PinStore(MapKv()).enabled) }
    @Test fun externalControlDisabledWhileOffEvenForPinnedController() {
        val s = PinStore(MapKv())
        val caller = CallerIdentity(10200, mapOf("ai.droidcommand.app" to listOf(cert.uppercase().chunked(2).joinToString(":"))))
        val env = JSONObject().put("op", "remote.command").put("requestId", "r1").put("targetNodeRef", "vfa-test").put("actionRef", "status.get")
            .put("params", JSONObject()).put("nonce", "%032x".format(7)).put("timestampMs", 1_800_000_000_000L).put("ttlMs", 120000).put("destructive", false)
        val d = RemoteDispatcher(s, FakeActions(), NonceCache(clock = { 1_800_000_000_000L }), { 1_800_000_000_000L })
        assertEquals("external_control_disabled", d.handleJson(env.toString(), caller).optJSONObject("error")?.getString("code"))
    }
    @Test fun removalPersistsAndIsNotReseeded() {
        val kv = MapKv()
        PinStore(kv).setControllers(emptyList())
        val again = PinStore(kv)
        assertTrue(again.controllers.isEmpty())
        assertFalse(again.enabled)
    }
    @Test fun seedKeepsExistingPinsAndDoesNotDuplicate() {
        val kv = MapKv(); val other = PinnedController("com.fleet.x", "ab".repeat(32))
        kv.putString(PinStore.CONTROLLERS, PinnedController.listToJson(listOf(other, PinnedController("ai.droidcommand.app", cert.uppercase()))))
        assertEquals(2, PinStore(kv).controllers.size)
    }
}
