package com.victorious.flash.android.gadget

/** Ties probe + scripts + root shell together. Stateless apart from the on-disk backup dir. */
class GadgetController(private val shell: RootShell, private val backupDir: String) {

    sealed class Outcome {
        data class Ok(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun expose(caps: Capabilities, file: String, cdrom: Boolean, allowedRoots: List<String>): Outcome {
        if (!caps.gadgetModeUsable) return Outcome.Failed("Gadget mode is not available on this phone.")
        val safe = PathSafety.checkString(file, allowedRoots)
        if (safe is PathSafety.Verdict.Refused) return Outcome.Failed("Refused: ${safe.reason}")
        val script = when (caps.backend) {
            GadgetBackend.CONFIGFS -> {
                val t = resolveTarget() ?: return Outcome.Failed("Could not find a gadget config to use.")
                GadgetScripts.configfsExpose(t, file, cdrom, backupDir)
            }
            GadgetBackend.LEGACY_ANDROID_USB -> GadgetScripts.legacyExpose(file, cdrom, backupDir)
            GadgetBackend.NONE -> return Outcome.Failed("No gadget backend.")
        }
        val r = shell.run(script)
        return if (r.ok && r.stdout.contains("EXPOSED")) Outcome.Ok("Exposed read-only as ${if (cdrom) "CD-ROM" else "USB disk"}.")
        else Outcome.Failed("Expose failed (exit ${r.exitCode}): ${r.stderr.trim().take(300)}")
    }

    fun restore(backend: GadgetBackend): Outcome {
        val script = when (backend) {
            GadgetBackend.CONFIGFS -> {
                val t = resolveTarget() ?: return Outcome.Failed("Could not find the gadget config to restore.")
                GadgetScripts.configfsRestore(t, backupDir)
            }
            GadgetBackend.LEGACY_ANDROID_USB -> GadgetScripts.legacyRestore(backupDir)
            GadgetBackend.NONE -> return Outcome.Ok("Nothing to restore.")
        }
        val r = shell.run(script)
        return if (r.stdout.contains("RESTORED")) Outcome.Ok("USB restored.") else Outcome.Failed("Restore failed: ${r.stderr.trim().take(300)}. Rebooting the phone also restores USB.")
    }

    fun hasBackup(): Boolean = shell.run("[ -f ${GadgetScripts.quote("$backupDir/udc")} ] || [ -f ${GadgetScripts.quote("$backupDir/functions")} ]").ok

    private fun resolveTarget(): GadgetScripts.ConfigfsTarget? {
        val listing = shell.run(
            "for g in /config/usb_gadget/*; do n=\$(basename \"\$g\"); echo \"G \$n \$(cat \"\$g/UDC\" 2>/dev/null)\"; " +
                "for c in \"\$g\"/configs/*; do echo \"C \$n \$(basename \"\$c\")\"; done; done; " +
                "for u in /sys/class/udc/*; do echo \"U \$(basename \"\$u\")\"; done; " +
                "cat ${GadgetScripts.quote("$backupDir/udc")} 2>/dev/null | sed 's/^/B /'",
        ).stdout
        return chooseTarget(listing)
    }

    companion object {
        /**
         * Parses the listing (`G <gadget> [udc]`, `C <gadget> <config>`, `U <udc>`, `B <udc>`) and
         * picks the gadget that is currently bound (else the first), its first config, and the
         * UDC from the backup, the bound gadget, or the first controller.
         */
        fun chooseTarget(listing: String): GadgetScripts.ConfigfsTarget? {
            val gadgets = mutableListOf<Pair<String, String>>()
            val configs = mutableMapOf<String, MutableList<String>>()
            val udcs = mutableListOf<String>()
            var backupUdc: String? = null
            for (line in listing.lines()) {
                val p = line.trim().split(Regex("\\s+"))
                when (p.firstOrNull()) {
                    "G" -> if (p.size >= 2 && p[1] != "*") gadgets += p[1] to p.getOrElse(2) { "" }
                    "C" -> if (p.size >= 3 && p[2] != "*") configs.getOrPut(p[1]) { mutableListOf() } += p[2]
                    "U" -> if (p.size >= 2 && p[1] != "*") udcs += p[1]
                    "B" -> if (p.size >= 2) backupUdc = p[1]
                }
            }
            val g = gadgets.firstOrNull { it.second.isNotEmpty() } ?: gadgets.firstOrNull() ?: return null
            val c = configs[g.first]?.firstOrNull() ?: return null
            val udc = backupUdc ?: g.second.ifEmpty { null } ?: udcs.firstOrNull() ?: return null
            return runCatching { GadgetScripts.ConfigfsTarget(g.first, c, udc) }.getOrNull()
        }
    }
}
