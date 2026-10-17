package com.victorious.flash.android.netboot

import java.io.File

/**
 * The "lighter" boot path for firmwares that only accept .efi URLs (e.g. Dell Manual Mode):
 * firmware loads Ubuntu's signed shim from /boot/bootx64.efi, shim fetches /boot/grubx64.efi
 * from the same URL directory, GRUB reads a generated grub.cfg from this server, then loads the
 * kernel + initrd, and casper downloads the ISO (iso-url=) into RAM.
 */
object EfiBoot {
    const val SHIM = "/boot/bootx64.efi"

    /** Served path -> candidate paths inside an Ubuntu ISO (first hit wins). */
    private val wanted: Map<String, List<String>> = mapOf(
        SHIM to listOf("/EFI/boot/bootx64.efi"),
        "/boot/grubx64.efi" to listOf("/EFI/boot/grubx64.efi"),
        "/boot/mmx64.efi" to listOf("/EFI/boot/mmx64.efi"),
        "/casper/vmlinuz" to listOf("/casper/vmlinuz", "/casper/hwe-vmlinuz"),
        "/casper/initrd" to listOf("/casper/initrd", "/casper/hwe-initrd", "/casper/initrd.gz", "/casper/initrd.lz"),
    )
    private val required = listOf(SHIM, "/boot/grubx64.efi", "/casper/vmlinuz", "/casper/initrd")

    /**
     * Where GRUB looks for grub.cfg over HTTP: Ubuntu's netboot GRUB uses $prefix=(http,server)/grub
     * (server root, not the .efi's directory); the ISO's CD GRUB falls back to ($root)/boot/grub.
     * In the QEMU/OVMF run, Ubuntu 24.04's CD GRUB 2.12 probed HEAD /grub.cfg (server root). Serve the same config at all of them.
     */
    val grubCfgPaths = listOf("/grub.cfg", "/grub/grub.cfg", "/grub/x86_64-efi/grub.cfg", "/boot/grub/grub.cfg", "/boot/grub/x86_64-efi/grub.cfg")

    data class Plan(val files: Map<String, Iso9660.Entry>, val missing: List<String>) {
        val usable get() = missing.isEmpty()
    }

    fun plan(iso: File): Plan = runCatching {
        Iso9660(iso).use { r ->
            val found = wanted.mapNotNull { (served, cands) -> cands.firstNotNullOfOrNull { r.find(it) }?.let { served to it } }.toMap()
            Plan(found, required.filter { it !in found })
        }
    }.getOrElse { Plan(emptyMap(), required) }

    /**
     * grub.cfg. Kernel/initrd paths are relative so GRUB fetches them from the same (http,…) device
     * it was loaded from. [officialIsoUrl] adds a menu entry that downloads the ISO from Ubuntu instead
     * (needs internet through the phone's tethering; same RAM need, not hash-checked by casper).
     */
    fun grubCfg(phoneIsoUrl: String, officialIsoUrl: String?): String = buildString {
        appendLine("set timeout=30")
        appendLine("set default=0")
        appendLine("echo 'Victorious Flash netboot. Installing Ubuntu can ERASE the PC disk. Read every installer screen.'")
        appendLine(entry("Ubuntu installer - ISO from this phone (offline; PC needs RAM > ISO size + ~2 GB)", phoneIsoUrl))
        if (officialIsoUrl != null) appendLine(entry("Ubuntu installer - ISO from releases.ubuntu.com (needs phone mobile data; same RAM need)", officialIsoUrl))
        appendLine("menuentry 'Reboot' { reboot }")
        appendLine("menuentry 'UEFI firmware settings' { fwsetup }")
    }

    private fun entry(title: String, isoUrl: String) = """
        |menuentry '$title' {
        |    set gfxpayload=keep
        |    echo 'Loading kernel...'
        |    linux /casper/vmlinuz ip=dhcp iso-url=$isoUrl cloud-config-url=/dev/null ---
        |    echo 'Loading initrd...'
        |    initrd /casper/initrd
        |}""".trimMargin()

    /** Only offer the official URL when it is an https releases.ubuntu.com/cdimage .iso link. */
    fun officialUrlOrNull(u: String?): String? = u?.takeIf {
        Regex("^https://(releases|cdimage)\\.ubuntu\\.com/[A-Za-z0-9._/+-]+\\.iso$").matches(it)
    }

    /** The exact route table ServeService uses: raw ISO + (if usable) EFI files and grub.cfg. */
    fun routes(f: File, urlPath: String, officialUrl: String?): Pair<Map<String, Resource>, Plan> {
        val routes = LinkedHashMap<String, Resource>()
        routes[urlPath] = Resource.Slice(f, 0, f.length(), IsoHttpServer.contentType(f.name))
        val plan = if (f.extension.equals("iso", true)) plan(f) else Plan(emptyMap(), listOf("not an .iso"))
        if (plan.usable) {
            plan.files.forEach { (served, e) -> routes[served] = Resource.Slice(f, e.offset, e.length, IsoHttpServer.contentType(served)) }
            val cfg = Resource.Generated("text/plain") { hostPort -> grubCfg("http://$hostPort$urlPath", officialUrl).toByteArray() }
            grubCfgPaths.forEach { routes[it] = cfg }
        }
        return routes to plan
    }
}
