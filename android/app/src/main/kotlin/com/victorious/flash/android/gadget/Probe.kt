package com.victorious.flash.android.gadget

/** How the kernel exposes USB gadget configuration. */
enum class GadgetBackend { CONFIGFS, LEGACY_ANDROID_USB, NONE }

/** Result of the capability probe, shown to the user as a checklist. */
data class Capabilities(
    val rooted: Boolean,
    val backend: GadgetBackend,
    val configfsGadgets: List<String>,
    val massStorageSupported: Boolean,
    val udcs: List<String>,
    val notes: List<String>,
) {
    /** P1 (phone as USB disk) can work only if all of these hold. */
    val gadgetModeUsable: Boolean
        get() = rooted && backend != GadgetBackend.NONE && massStorageSupported && udcs.isNotEmpty()
}

/**
 * Probes USB-gadget capabilities via a [RootShell]. All parsing is pure and unit-tested through
 * [parse]; the only impure part is gathering command output.
 */
class GadgetProbe(private val shell: RootShell) {

    fun probe(): Capabilities {
        val rooted = shell.run("id -u").let { it.ok && it.stdout.trim() == "0" }
        if (!rooted) {
            return Capabilities(false, GadgetBackend.NONE, emptyList(), false, emptyList(),
                listOf("Root (su) is not available. Gadget mode needs root to reconfigure USB."))
        }
        val raw = RawProbe(
            configfsLs = shell.run("ls -1 /config/usb_gadget 2>/dev/null").stdout,
            modulesAndFunctions = shell.run("cat /proc/modules 2>/dev/null; echo ---; ls -1 /config/usb_gadget/*/functions 2>/dev/null; echo ---; cat /sys/module/usb_f_mass_storage/initstate 2>/dev/null").stdout,
            udcLs = shell.run("ls -1 /sys/class/udc 2>/dev/null").stdout,
            legacyLs = shell.run("ls -1 /sys/class/android_usb/android0 2>/dev/null").stdout,
            legacyFunctions = shell.run("cat /sys/class/android_usb/android0/functions 2>/dev/null").stdout,
        )
        return parse(rooted = true, raw)
    }

    /** Everything the probe reads, so [parse] can be tested without a device. */
    data class RawProbe(
        val configfsLs: String,
        val modulesAndFunctions: String,
        val udcLs: String,
        val legacyLs: String,
        val legacyFunctions: String,
    )

    companion object {
        fun parse(rooted: Boolean, raw: RawProbe): Capabilities {
            val notes = mutableListOf<String>()
            val gadgets = raw.configfsLs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("ls:") }.toList()
            val udcs = raw.udcLs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("ls:") }.toList()
            val legacyPresent = raw.legacyLs.lineSequence().any { it.trim() == "functions" }

            val backend = when {
                gadgets.isNotEmpty() -> GadgetBackend.CONFIGFS
                legacyPresent -> GadgetBackend.LEGACY_ANDROID_USB
                else -> GadgetBackend.NONE
            }

            // mass_storage support: an existing mass_storage.N function, the usb_f_mass_storage
            // module loaded (/proc/modules or initstate "live"), or the legacy functions list
            // advertising mass_storage.
            val sections = raw.modulesAndFunctions.split("---")
            val modules = sections.getOrElse(0) { "" }
            val functions = sections.getOrElse(1) { "" }.lineSequence().map { it.trim() }
            val initstate = sections.getOrElse(2) { "" }.trim()
            val massStorageSupported = when (backend) {
                GadgetBackend.CONFIGFS ->
                    modules.lineSequence().any { it.startsWith("usb_f_mass_storage ") } ||
                        functions.any { it.startsWith("mass_storage.") } ||
                        initstate == "live"
                GadgetBackend.LEGACY_ANDROID_USB ->
                    raw.legacyLs.lineSequence().any { it.trim() == "f_mass_storage" } || raw.legacyFunctions.contains("mass_storage")
                GadgetBackend.NONE -> false
            }

            when (backend) {
                GadgetBackend.NONE -> notes += "No configfs usb_gadget and no legacy android_usb. This phone's kernel can't be reconfigured as a USB disk."
                GadgetBackend.CONFIGFS -> notes += "Modern configfs gadget backend detected (${gadgets.size} gadget(s))."
                GadgetBackend.LEGACY_ANDROID_USB -> notes += "Legacy android_usb backend detected."
            }
            if (backend != GadgetBackend.NONE && !massStorageSupported)
                notes += "The kernel has no mass_storage USB function. Gadget mode can't work without a custom kernel."
            if (backend != GadgetBackend.NONE && udcs.isEmpty())
                notes += "No USB Device Controller (UDC) found under /sys/class/udc."

            return Capabilities(rooted, backend, gadgets, massStorageSupported, udcs, notes)
        }
    }
}
