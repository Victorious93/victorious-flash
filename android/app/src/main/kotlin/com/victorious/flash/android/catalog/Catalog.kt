package com.victorious.flash.android.catalog

import org.json.JSONArray

/** Gadget-mode tags from catalog/distros.json (`"gadget": ["gadget-disk", "gadget-cdrom"]`). */
enum class GadgetTag(val wire: String) { DISK("gadget-disk"), CDROM("gadget-cdrom") }

data class CatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val isoUrl: String,
    val checksumUrl: String,
    val isoFilename: String,
    val sha256: String?,
    val gadget: Set<GadgetTag>,
) {
    /** Hybrid ISOs boot as a disk; CD-ROM is the fallback for ISO-only images. */
    val defaultCdrom: Boolean get() = GadgetTag.DISK !in gadget && GadgetTag.CDROM in gadget
}

object Catalog {
    /** Parses the shared catalog and keeps only entries tagged for gadget mode. */
    fun parseGadgetEntries(json: String): List<CatalogEntry> {
        val arr = JSONArray(json)
        val out = mutableListOf<CatalogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tags = o.optJSONArray("gadget")?.let { a -> (0 until a.length()).mapNotNull { j -> GadgetTag.entries.firstOrNull { it.wire == a.optString(j) } } }.orEmpty().toSet()
            if (tags.isEmpty()) continue
            val url = o.getString("iso_url"); val sums = o.getString("checksum_url")
            if (!url.startsWith("https://") || !sums.startsWith("https://")) continue
            out += CatalogEntry(
                id = o.getString("id"), name = o.getString("name"), version = o.optString("version"),
                description = o.optString("description"), isoUrl = url, checksumUrl = sums,
                isoFilename = o.getString("iso_filename"),
                sha256 = if (o.isNull("sha256")) null else o.optString("sha256").takeIf { it.length == 64 },
                gadget = tags,
            )
        }
        return out
    }
}
