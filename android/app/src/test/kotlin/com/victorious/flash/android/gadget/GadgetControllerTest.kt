package com.victorious.flash.android.gadget

import org.junit.Assert.*
import org.junit.Test

class GadgetControllerTest {
    private class Fake(val replies: (String) -> RootShell.Result = { RootShell.Result(0, "EXPOSED", "") }) : RootShell {
        val ran = mutableListOf<String>()
        override fun run(command: String): RootShell.Result { ran += command; return replies(command) }
    }
    private fun caps(cdrom: Boolean? = null, usable: Boolean = true) = Capabilities(
        rooted = true, backend = if (usable) GadgetBackend.LEGACY_ANDROID_USB else GadgetBackend.NONE, configfsGadgets = emptyList(),
        massStorageSupported = usable, udcs = listOf("musb"), notes = emptyList(), cdromSupported = cdrom)
    private val roots = listOf("/data/data/app/files/images")
    private val img = "/data/data/app/files/images/exposed.iso"

    @Test fun cdromRefusedWhenKernelSaysNoAndNothingRunsAsRoot() {
        val sh = Fake()
        val r = GadgetController(sh, "/data/b").expose(caps(cdrom = false), img, true, roots)
        assertTrue(r is GadgetController.Outcome.Failed)
        assertTrue(sh.ran.isEmpty())
        // disk mode is still allowed on that kernel
        assertTrue(GadgetController(Fake(), "/data/b").expose(caps(cdrom = false), img, false, roots) is GadgetController.Outcome.Ok)
    }

    @Test fun unknownCdromSupportStillTriesAndReportsKernelFailureHonestly() {
        val sh = Fake { RootShell.Result(8, "", "kernel has no CD-ROM mode") }
        val r = GadgetController(sh, "/data/b").expose(caps(cdrom = null), img, true, roots)
        assertTrue(r is GadgetController.Outcome.Failed)
        assertTrue((r as GadgetController.Outcome.Failed).message.contains("kernel has no CD-ROM mode"))
    }

    @Test fun neverExposesBlockDevicesOrPathsOutsideTheImageFolder() {
        for (bad in listOf("/dev/block/mmcblk0", "/sdcard/x.iso", "/data/data/app/files/images/../secret.iso", "/system/x.iso", "/data/data/app/files/images/a b.iso")) {
            val sh = Fake()
            assertTrue("$bad", GadgetController(sh, "/data/b").expose(caps(), bad, false, roots) is GadgetController.Outcome.Failed)
            assertTrue("$bad ran root commands", sh.ran.isEmpty())
        }
    }

    @Test fun unusableGadgetModeRunsNothing() {
        val sh = Fake()
        assertTrue(GadgetController(sh, "/data/b").expose(caps(usable = false), img, false, roots) is GadgetController.Outcome.Failed)
        assertTrue(sh.ran.isEmpty())
    }

    @Test fun exposeScriptIsReadOnlyBacksUpFirstAndRestoresOnEveryFailureStep() {
        val script = GadgetScripts.configfsExpose(GadgetScripts.ConfigfsTarget("g1", "b.1", "musb"), img, true, "/data/b")
        assertTrue(script.contains("echo 1 > \"\$M/lun.0/ro\""))
        assertTrue(script.indexOf("# 1. back up") < script.indexOf("# 2. unbind"))
        assertTrue("never overwrite an existing (original) backup", script.contains("if [ ! -f \"\$B/udc\" ]"))
        assertTrue("every post-detach step restores on failure", Regex("\\|\\| \\{ restore; exit").findAll(script).count() >= 5)
        assertTrue(script.contains("-b \"\$F\""))   // block devices refused inside the root script too
    }

    @Test fun restoreReportsFailureHonestly() {
        val sh = Fake { RootShell.Result(1, "", "device busy") }
        val r = GadgetController(sh, "/data/b").restore(GadgetBackend.LEGACY_ANDROID_USB)
        assertTrue(r is GadgetController.Outcome.Failed)
        assertTrue((r as GadgetController.Outcome.Failed).message.contains("Rebooting"))
    }

    @Test fun restoreWithNoBackendIsANoOp() {
        val sh = Fake()
        assertTrue(GadgetController(sh, "/data/b").restore(GadgetBackend.NONE) is GadgetController.Outcome.Ok)
        assertTrue(sh.ran.isEmpty())
    }
}
