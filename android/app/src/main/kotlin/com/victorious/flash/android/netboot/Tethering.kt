package com.victorious.flash.android.netboot

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** A local IPv4 address on an interface that a PC could reach. */
data class LocalAddr(val iface: String, val ip: String, val kind: Kind) {
    enum class Kind { USB_TETHER, HOTSPOT, OTHER }
}

object Tethering {
    /**
     * Classifies Android interface names. USB tethering shows up as rndis0 / ncm0 / usb0;
     * Wi-Fi hotspot as ap0 / swlan0 / softap0 / wlan1 (vendor-specific). Mobile data (rmnet*, ccmni*)
     * and loopback are never offered.
     */
    fun classify(name: String): LocalAddr.Kind? {
        val n = name.lowercase()
        return when {
            n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb") -> LocalAddr.Kind.USB_TETHER
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n == "wlan1" || n == "wlan2" -> LocalAddr.Kind.HOTSPOT
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("lo") || n.startsWith("dummy") ||
                n.startsWith("tun") || n.startsWith("ipsec") || n.startsWith("p2p") || n.startsWith("v4-") -> null
            else -> LocalAddr.Kind.OTHER
        }
    }

    /** Pure selection logic: keep tether/hotspot first, then other LAN (e.g. same Wi-Fi). */
    fun select(candidates: List<Pair<String, String>>): List<LocalAddr> =
        candidates.mapNotNull { (iface, ip) -> classify(iface)?.let { LocalAddr(iface, ip, it) } }
            .filter { isPrivateV4(it.ip) }
            .sortedBy { it.kind.ordinal }

    fun isPrivateV4(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** Reads live interfaces. May be empty when tethering is off. */
    fun current(): List<LocalAddr> = select(runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress!! }
        }
    }.getOrDefault(emptyList()))

    fun toInet(a: LocalAddr): InetAddress = InetAddress.getByName(a.ip)
}

/** Cheap root hint without invoking su (so no superuser prompt at launch). */
object RootHint {
    private val dirs = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/debug_ramdisk", "/data/adb/magisk")
    fun suBinaryPresent(extraPath: String? = System.getenv("PATH")): Boolean =
        (dirs + (extraPath?.split(':') ?: emptyList())).distinct().any { File(it, "su").exists() }
}
