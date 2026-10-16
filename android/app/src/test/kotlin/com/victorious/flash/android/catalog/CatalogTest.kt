package com.victorious.flash.android.catalog

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CatalogTest {
    @Test fun sharedCatalogHasGadgetEntries() {
        val json = File("../../catalog/distros.json").readText()
        val entries = Catalog.parseGadgetEntries(json)
        assertTrue(entries.any { it.id == "ubuntu" })
        assertTrue("raspberry pi images are not gadget images", entries.none { it.id.startsWith("raspios") })
        for (e in entries) {
            assertTrue(e.isoUrl.startsWith("https://")); assertTrue(e.gadget.isNotEmpty())
            assertFalse("hybrid ISOs default to disk", e.defaultCdrom)
        }
    }

    @Test fun cdromOnlyDefaultsToCdromAndUntaggedSkipped() {
        val j = """[{"id":"a","name":"A","iso_url":"https://e/a.iso","checksum_url":"https://e/S","iso_filename":"a.iso","sha256":null,"gadget":["gadget-cdrom"]},
                    {"id":"b","name":"B","iso_url":"https://e/b.iso","checksum_url":"https://e/S","iso_filename":"b.iso","sha256":null},
                    {"id":"c","name":"C","iso_url":"http://e/c.iso","checksum_url":"https://e/S","iso_filename":"c.iso","gadget":["gadget-disk"]}]"""
        val e = Catalog.parseGadgetEntries(j)
        assertEquals(listOf("a"), e.map { it.id })
        assertTrue(e[0].defaultCdrom)
    }
}
