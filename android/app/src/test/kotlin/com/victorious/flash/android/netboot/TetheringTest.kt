package com.victorious.flash.android.netboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TetheringTest {
    @Test fun classifiesInterfaces() {
        assertEquals(LocalAddr.Kind.USB_TETHER, Tethering.classify("rndis0"))
        assertEquals(LocalAddr.Kind.USB_TETHER, Tethering.classify("ncm0"))
        assertEquals(LocalAddr.Kind.HOTSPOT, Tethering.classify("swlan0"))
        assertEquals(LocalAddr.Kind.HOTSPOT, Tethering.classify("ap0"))
        assertNull(Tethering.classify("rmnet_data0"))
        assertNull(Tethering.classify("lo"))
    }

    @Test fun selectsPrivateAndOrdersTetherFirst() {
        val r = Tethering.select(listOf("wlan0" to "192.168.1.20", "rmnet0" to "10.1.2.3", "ap0" to "192.168.43.1", "rndis0" to "192.168.42.129", "wlan0" to "100.64.0.2"))
        assertEquals(listOf("rndis0", "ap0", "wlan0"), r.map { it.iface })
    }

    @Test fun privateRanges() {
        assertTrue(Tethering.isPrivateV4("172.20.0.1")); assertFalse(Tethering.isPrivateV4("172.32.0.1")); assertFalse(Tethering.isPrivateV4("8.8.8.8"))
    }
}
