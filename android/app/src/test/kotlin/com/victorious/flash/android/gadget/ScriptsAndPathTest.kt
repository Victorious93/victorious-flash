package com.victorious.flash.android.gadget

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScriptsAndPathTest {
    private val root = "/data/user/0/com.victorious.flash.android/files/images"
    private val t = GadgetScripts.ConfigfsTarget("g1", "b.1", "a600000.dwc3")

    @Test fun configfsExposeSetsReadOnlyRemovableAndBacksUpFirst() {
        val s = GadgetScripts.configfsExpose(t, "$root/exposed.iso", cdrom = false, backupDir = "/data/x/backup")
        val iBackup = s.indexOf("cat \"\$G/UDC\" > \"\$B/udc\"")
        val iUnbind = s.indexOf("echo \"\" > \"\$G/UDC\" 2>/dev/null\nfor")
        assertTrue("backup before unbind", iBackup in 0 until iUnbind)
        assertTrue(s.contains("echo 1 > \"\$M/lun.0/ro\""))
        assertTrue(s.contains("echo 1 > \"\$M/lun.0/removable\""))
        assertTrue(s.contains("echo 0 > \"\$M/lun.0/cdrom\""))
        assertTrue(s.contains("functions/mass_storage.vflash"))
        assertTrue(s.contains("echo 'a600000.dwc3' > \"\$G/UDC\""))
        assertTrue(s.contains("F='$root/exposed.iso'"))
        assertTrue("ro set before file", s.indexOf("lun.0/ro") < s.indexOf("lun.0/file\" ||"))
        assertTrue("refuses device paths in-script", s.contains("/dev/*|/proc/*|/sys/*"))
        assertTrue("refuses non-regular/symlink/block", s.contains("[ -L \"\$F\" ] || [ -b \"\$F\" ]"))
        assertTrue("restores on failure", s.contains("{ restore; exit 7; }"))
        assertTrue(GadgetScripts.configfsExpose(t, "$root/a.iso", true, "/b").contains("echo 1 > \"\$M/lun.0/cdrom\""))
    }

    @Test fun restoreRelinksOriginalFunctionsAndRebinds() {
        val s = GadgetScripts.configfsRestore(t, "/data/x/backup")
        assertTrue(s.contains("rm -f \"\$C/mass_storage.vflash\""))
        assertTrue(s.contains("ln -sf \"\$G/functions/\$fn\" \"\$C/\$name\""))
        assertTrue(s.contains("echo \"\$U\" > \"\$G/UDC\""))
        assertTrue(s.contains("setprop sys.usb.config"))
        assertTrue(s.trim().endsWith("echo RESTORED"))
    }

    @Test fun legacyScripts() {
        val e = GadgetScripts.legacyExpose("$root/a.img", false, "/b")
        assertTrue(e.contains("echo mass_storage > \"\$L/functions\""))
        assertTrue(e.contains("cat \"\$L/functions\" > \"\$B/functions\""))
        assertTrue(GadgetScripts.legacyExpose("$root/a.iso", true, "/b").contains("kernel has no CD-ROM mode"))
        assertTrue(GadgetScripts.legacyRestore("/b").contains("FN=mtp,adb"))
    }

    @Test fun quotingAndNameValidation() {
        assertEquals("'a'\\''b'", GadgetScripts.quote("a'b"))
        assertThrows(IllegalArgumentException::class.java) { t.copy(gadget = "g;reboot") }
        assertThrows(IllegalArgumentException::class.java) { t.copy(udc = "\$(id)") }
        assertThrows(IllegalArgumentException::class.java) { GadgetScripts.configfsExpose(t, "$root/a.iso", false, "/b/../c") }
    }

    @Test fun pathSafetyStrings() {
        val roots = listOf(root)
        assertTrue(PathSafety.checkString("$root/exposed.iso", roots) is PathSafety.Verdict.Ok)
        for (bad in listOf("/dev/block/sda", "/dev/block/by-name/userdata.img", "/proc/self/mem.img", "/sys/x.iso", "relative.iso",
            "$root/../../../../dev/block/sda.img", "$root/x.zip", "$root/a'b.iso", "$root/a b.iso", "/sdcard/Download/x.iso", "$root/./x.iso")) {
            assertTrue(bad, PathSafety.checkString(bad, roots) is PathSafety.Verdict.Refused)
        }
    }

    @Test fun pathSafetyFilesystem() {
        val dir = Files.createTempDirectory("imgs").toFile().canonicalFile
        val roots = listOf(dir.path)
        val ok = File(dir, "a.iso").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(PathSafety.check(ok, roots) is PathSafety.Verdict.Ok)
        assertTrue(PathSafety.check(File(dir, "missing.iso"), roots) is PathSafety.Verdict.Refused)
        assertTrue(PathSafety.check(File(dir, "empty.iso").apply { createNewFile() }, roots) is PathSafety.Verdict.Refused)
        val link = File(dir, "link.iso")
        Files.createSymbolicLink(link.toPath(), File("/etc/hostname").toPath())
        assertTrue(PathSafety.check(link, roots) is PathSafety.Verdict.Refused)
        assertTrue(PathSafety.check(File(dir, "sub.iso").apply { mkdir() }, roots) is PathSafety.Verdict.Refused)
    }

    @Test fun controllerRefusesUnsafePathWithoutRunningAnything() {
        val ran = mutableListOf<String>()
        val shell = object : RootShell { override fun run(command: String): RootShell.Result { ran += command; return RootShell.Result(0, "", "") } }
        val caps = Capabilities(true, GadgetBackend.CONFIGFS, listOf("g1"), true, listOf("u"), emptyList())
        val r = GadgetController(shell, "/b").expose(caps, "/dev/block/sda", false, listOf(root))
        assertTrue(r is GadgetController.Outcome.Failed)
        assertTrue(ran.isEmpty())
    }
}
