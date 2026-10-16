package com.victorious.flash.android.gadget

import java.io.File

/**
 * Only regular image files inside the app's own storage may ever be handed to the kernel's
 * mass_storage function. Phone partitions and block devices are never exposed.
 */
object PathSafety {
    private val FORBIDDEN_PREFIXES = listOf("/dev/", "/proc/", "/sys/", "/config/", "/system/", "/vendor/", "/apex/")
    private val SAFE_CHARS = Regex("^[A-Za-z0-9/._+-]+$")

    sealed class Verdict {
        data class Ok(val path: String) : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    /** Pure string checks, also used inside the generated root scripts. */
    fun checkString(path: String, allowedRoots: List<String>): Verdict {
        if (!path.startsWith("/")) return Verdict.Refused("path must be absolute")
        if (!SAFE_CHARS.matches(path)) return Verdict.Refused("path contains unsupported characters")
        if (path.split('/').any { it == ".." || it == "." }) return Verdict.Refused("path must not contain . or .. segments")
        if (FORBIDDEN_PREFIXES.any { path.startsWith(it) } || path.startsWith("/dev")) return Verdict.Refused("device and system paths are never exposed")
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("iso", "img")) return Verdict.Refused("only .iso or .img files can be exposed")
        val roots = allowedRoots.map { it.trimEnd('/') + "/" }
        if (roots.none { path.startsWith(it) }) return Verdict.Refused("image must be inside the app's image folder")
        return Verdict.Ok(path)
    }

    /** String checks plus filesystem checks: regular file, not a symlink, canonical path unchanged. */
    fun check(file: File, allowedRoots: List<String>): Verdict {
        val s = checkString(file.path, allowedRoots)
        if (s is Verdict.Refused) return s
        if (!file.isFile) return Verdict.Refused("not a regular file")
        if (file.canonicalPath != file.absolutePath) return Verdict.Refused("symlinks are not allowed")
        if (file.length() == 0L) return Verdict.Refused("file is empty")
        return Verdict.Ok(file.path)
    }
}
