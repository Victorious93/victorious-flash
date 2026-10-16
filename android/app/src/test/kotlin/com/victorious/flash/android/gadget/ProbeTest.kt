package com.victorious.flash.android.gadget

import org.junit.Assert.*
import org.junit.Test

class ProbeTest {
    private fun raw(cfg: String = "", mf: String = "---\n---\n", udc: String = "", legacy: String = "", lf: String = "") =
        GadgetProbe.RawProbe(cfg, mf, udc, legacy, lf)

    @Test fun configfsWithMassStorageModuleIsUsable() {
        val c = GadgetProbe.parse(true, raw(cfg = "g1\n", mf = "usb_f_mass_storage 40960 0 - Live 0x0\nusb_f_fs 1 0\n---\nffs.adb\nmtp.gs0\n---\n", udc = "a600000.dwc3\n"))
        assertEquals(GadgetBackend.CONFIGFS, c.backend)
        assertTrue(c.massStorageSupported)
        assertEquals(listOf("a600000.dwc3"), c.udcs)
        assertTrue(c.gadgetModeUsable)
    }

    @Test fun builtInMassStorageDetectedByExistingFunctionOrInitstate() {
        assertTrue(GadgetProbe.parse(true, raw(cfg = "g1", mf = "---\nmass_storage.0\nffs.adb\n---\n", udc = "musb")).massStorageSupported)
        assertTrue(GadgetProbe.parse(true, raw(cfg = "g1", mf = "---\n---\nlive\n", udc = "musb")).massStorageSupported)
    }

    @Test fun configfsWithoutMassStorageExplainsWhy() {
        val c = GadgetProbe.parse(true, raw(cfg = "g1", mf = "usb_f_fs 1 0\n---\nffs.adb\n---\n", udc = "x"))
        assertFalse(c.massStorageSupported)
        assertFalse(c.gadgetModeUsable)
        assertTrue(c.notes.any { it.contains("no mass_storage") })
    }

    @Test fun legacyAndroidUsb() {
        val c = GadgetProbe.parse(true, raw(legacy = "enable\nfunctions\nf_mass_storage\nf_mtp\n", lf = "mtp,adb", udc = "musb-hdrc"))
        assertEquals(GadgetBackend.LEGACY_ANDROID_USB, c.backend)
        assertTrue(c.massStorageSupported)
        assertTrue(c.gadgetModeUsable)
    }

    @Test fun nothingAndNoUdc() {
        val c = GadgetProbe.parse(true, raw(cfg = "ls: /config/usb_gadget: No such file or directory"))
        assertEquals(GadgetBackend.NONE, c.backend)
        assertFalse(c.gadgetModeUsable)
        val noUdc = GadgetProbe.parse(true, raw(cfg = "g1", mf = "usb_f_mass_storage 1 0\n---\n---\n"))
        assertFalse(noUdc.gadgetModeUsable)
        assertTrue(noUdc.notes.any { it.contains("UDC") })
    }

    @Test fun noRootShortCircuits() {
        val shell = object : RootShell { override fun run(command: String) = RootShell.Result(1, "", "su: not found") }
        val c = GadgetProbe(shell).probe()
        assertFalse(c.rooted); assertFalse(c.gadgetModeUsable)
    }

    @Test fun chooseTargetPrefersBoundGadgetAndBackupUdc() {
        val listing = "G g0 \nC g0 c.1\nG g1 a600000.dwc3\nC g1 b.1\nU a600000.dwc3\n"
        assertEquals(GadgetScripts.ConfigfsTarget("g1", "b.1", "a600000.dwc3"), GadgetController.chooseTarget(listing))
        // While exposed the gadget is bound to our function; the backup UDC still wins.
        assertEquals("orig.udc", GadgetController.chooseTarget("G g1 \nC g1 b.1\nU x\nB orig.udc\n")!!.udc)
        assertNull(GadgetController.chooseTarget("G * \nU *\n"))
        assertNull(GadgetController.chooseTarget("G g\$(reboot) x\nC g\$(reboot) b.1\n"))
    }
}
