package com.victorious.flash.android.gadget

/**
 * Generates the root shell scripts that expose ONE image read-only and restore the original USB
 * setup afterwards. Every value is validated and single-quoted. Each expose script backs up
 * first, re-checks the file (regular, not a symlink, not a block device, not under /dev /proc
 * /sys), and on any failure runs the restore steps before exiting non-zero.
 */
object GadgetScripts {
    const val FUNCTION = "mass_storage.vflash"
    private val NAME_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    data class ConfigfsTarget(val gadget: String, val config: String, val udc: String) {
        init {
            require(NAME_RE.matches(gadget)) { "invalid gadget name" }
            require(NAME_RE.matches(config)) { "invalid config name" }
            require(NAME_RE.matches(udc)) { "invalid udc name" }
        }
    }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun requireName(kind: String, v: String) = require(NAME_RE.matches(v)) { "invalid $kind name" }

    private fun fileGuard(file: String) = """
        F=${quote(file)}
        case "${'$'}F" in /dev/*|/proc/*|/sys/*|/config/*) echo "refused: device path" >&2; exit 3;; esac
        if [ ! -f "${'$'}F" ] || [ -L "${'$'}F" ] || [ -b "${'$'}F" ]; then echo "refused: not a regular file" >&2; exit 3; fi
    """.trimIndent()

    /** configfs: back up UDC + function links, replace links with our read-only mass_storage, rebind. */
    fun configfsExpose(t: ConfigfsTarget, file: String, cdrom: Boolean, backupDir: String): String {
        requireName("gadget", t.gadget); requireName("config", t.config); requireName("udc", t.udc)
        require(backupDir.startsWith("/") && !backupDir.contains("..")) { "invalid backup dir" }
        return """
            |set -u
            |${fileGuard(file)}
            |G=${quote("/config/usb_gadget/${t.gadget}")}
            |C="${'$'}G/configs/${t.config}"
            |B=${quote(backupDir)}
            |[ -d "${'$'}C" ] || { echo "no such gadget config" >&2; exit 4; }
            |mkdir -p "${'$'}B" || exit 5
            |# 1. back up (never overwrite an existing backup: it is the ORIGINAL state)
            |if [ ! -f "${'$'}B/udc" ]; then
            |  cat "${'$'}G/UDC" > "${'$'}B/udc"
            |  getprop sys.usb.config > "${'$'}B/usbconfig" 2>/dev/null
            |  : > "${'$'}B/links"
            |  for l in "${'$'}C"/*; do [ -L "${'$'}l" ] && echo "${'$'}(basename "${'$'}l") ${'$'}(basename "${'$'}(readlink "${'$'}l")")" >> "${'$'}B/links"; done
            |fi
            |restore() {
            |${restoreBody().prependIndent("  ")}
            |}
            |# 2. unbind and detach current functions (MTP/ADB stop here)
            |echo "" > "${'$'}G/UDC" 2>/dev/null
            |for l in "${'$'}C"/*; do [ -L "${'$'}l" ] && rm "${'$'}l"; done
            |# 3. our function: read-only, removable, ${if (cdrom) "CD-ROM" else "disk"}
            |M="${'$'}G/functions/$FUNCTION"
            |mkdir -p "${'$'}M" || { restore; exit 6; }
            |echo 1 > "${'$'}M/lun.0/removable" || { restore; exit 6; }
            |echo ${if (cdrom) 1 else 0} > "${'$'}M/lun.0/cdrom" || { restore; exit 6; }
            |echo 1 > "${'$'}M/lun.0/ro" || { restore; exit 6; }
            |echo "${'$'}F" > "${'$'}M/lun.0/file" || { restore; exit 6; }
            |ln -s "${'$'}M" "${'$'}C/$FUNCTION" || { restore; exit 6; }
            |# 4. bind
            |echo ${quote(t.udc)} > "${'$'}G/UDC" || { restore; exit 7; }
            |echo EXPOSED
        """.trimMargin()
    }

    private fun restoreBody(): String = """
        |echo "" > "${'$'}G/UDC" 2>/dev/null
        |rm -f "${'$'}C/$FUNCTION"
        |M="${'$'}G/functions/$FUNCTION"
        |[ -d "${'$'}M" ] && { echo "" > "${'$'}M/lun.0/file" 2>/dev/null; rmdir "${'$'}M" 2>/dev/null; }
        |if [ -f "${'$'}B/links" ]; then
        |  while read -r name fn; do [ -n "${'$'}fn" ] && [ -e "${'$'}G/functions/${'$'}fn" ] && ln -sf "${'$'}G/functions/${'$'}fn" "${'$'}C/${'$'}name"; done < "${'$'}B/links"
        |fi
        |U=${'$'}(cat "${'$'}B/udc" 2>/dev/null)
        |[ -n "${'$'}U" ] && echo "${'$'}U" > "${'$'}G/UDC" 2>/dev/null
        |# Let the Android USB HAL re-apply its own state (MTP/ADB) if it manages the gadget.
        |P=${'$'}(cat "${'$'}B/usbconfig" 2>/dev/null)
        |[ -n "${'$'}P" ] && setprop sys.usb.config "${'$'}P" 2>/dev/null
    """.trimMargin()

    /** configfs restore: remove our function, re-link the original functions, rebind original UDC. */
    fun configfsRestore(t: ConfigfsTarget, backupDir: String): String {
        requireName("gadget", t.gadget); requireName("config", t.config)
        return """
            |set -u
            |G=${quote("/config/usb_gadget/${t.gadget}")}
            |C="${'$'}G/configs/${t.config}"
            |B=${quote(backupDir)}
            |${restoreBody()}
            |rm -f "${'$'}B/udc" "${'$'}B/links" "${'$'}B/usbconfig"
            |echo RESTORED
        """.trimMargin()
    }

    /** Legacy android_usb. CD-ROM only if the kernel exposes lun/cdrom. */
    fun legacyExpose(file: String, cdrom: Boolean, backupDir: String): String = """
        |set -u
        |${fileGuard(file)}
        |L=/sys/class/android_usb/android0
        |B=${quote(backupDir)}
        |mkdir -p "${'$'}B" || exit 5
        |[ -f "${'$'}B/functions" ] || { cat "${'$'}L/functions" > "${'$'}B/functions"; cat "${'$'}L/enable" > "${'$'}B/enable"; }
        |${if (cdrom) "[ -e \"\$L/f_mass_storage/lun/cdrom\" ] || { echo \"kernel has no CD-ROM mode\" >&2; exit 8; }" else ":"}
        |echo 0 > "${'$'}L/enable"
        |echo mass_storage > "${'$'}L/functions"
        |[ -e "${'$'}L/f_mass_storage/lun/ro" ] && echo 1 > "${'$'}L/f_mass_storage/lun/ro"
        |${if (cdrom) "echo 1 > \"\$L/f_mass_storage/lun/cdrom\"" else "[ -e \"\$L/f_mass_storage/lun/cdrom\" ] && echo 0 > \"\$L/f_mass_storage/lun/cdrom\""}
        |echo "${'$'}F" > "${'$'}L/f_mass_storage/lun/file" || exit 6
        |echo 1 > "${'$'}L/enable"
        |echo EXPOSED
    """.trimMargin()

    fun legacyRestore(backupDir: String): String = """
        |set -u
        |L=/sys/class/android_usb/android0
        |B=${quote(backupDir)}
        |echo 0 > "${'$'}L/enable"
        |echo "" > "${'$'}L/f_mass_storage/lun/file" 2>/dev/null
        |FN=${'$'}(cat "${'$'}B/functions" 2>/dev/null); [ -n "${'$'}FN" ] || FN=mtp,adb
        |echo "${'$'}FN" > "${'$'}L/functions"
        |echo 1 > "${'$'}L/enable"
        |rm -f "${'$'}B/functions" "${'$'}B/enable"
        |echo RESTORED
    """.trimMargin()
}
