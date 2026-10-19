package com.victorious.flash.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.flash.android.catalog.Catalog
import com.victorious.flash.android.catalog.CatalogEntry
import com.victorious.flash.android.gadget.Capabilities
import com.victorious.flash.android.gadget.GadgetController
import com.victorious.flash.android.gadget.GadgetProbe
import com.victorious.flash.android.gadget.GadgetService
import com.victorious.flash.android.gadget.SuRootShell
import com.victorious.flash.android.image.ImageVerifier
import com.victorious.flash.android.netboot.RootHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ImageState(
    val file: File? = null,
    val displayName: String = "",
    val bytes: Long = 0,
    val sha256: String? = null,
    val progress: Long = 0,
    val importing: Boolean = false,
)

enum class Mode { ROOTED, NO_ROOT }

data class UiState(
    val mode: Mode = Mode.NO_ROOT,
    val suHint: Boolean = false,
    val probing: Boolean = false,
    val caps: Capabilities? = null,
    val hasBackup: Boolean = false,
    val catalog: List<CatalogEntry> = emptyList(),
    val selected: CatalogEntry? = null,
    val expectedSha: String = "",
    val expectedSource: String = "",
    val image: ImageState = ImageState(),
    val cdrom: Boolean = false,
    val error: String = "",
) {
    val verified: Boolean get() = image.sha256 != null && expectedSha.isNotBlank() && image.sha256.equals(expectedSha.trim(), ignoreCase = true)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        // Auto-detect: if an su binary exists, default to rooted mode and run the full probe (may show the
        // superuser prompt). Otherwise default to No-root. The user can switch modes either way.
        val su = RootHint.suBinaryPresent()
        _ui.value = _ui.value.copy(suHint = su, mode = if (su) Mode.ROOTED else Mode.NO_ROOT)
        if (su) probe()
        viewModelScope.launch(Dispatchers.IO) {
            val json = app.assets.open("distros.json").bufferedReader().readText()
            _ui.value = _ui.value.copy(catalog = runCatching { Catalog.parseGadgetEntries(json) }.getOrDefault(emptyList()))
        }
    }

    fun probe() = viewModelScope.launch(Dispatchers.IO) {
        _ui.value = _ui.value.copy(probing = true)
        val shell = SuRootShell()
        val caps = GadgetProbe(shell).probe()
        GadgetService.capabilities = caps
        val backup = caps.rooted && GadgetController(shell, GadgetService.backupDir(getApplication())).hasBackup()
        // Root present but gadget unusable (kernel/UDC): fall back to No-root as the suggested mode.
        val mode = if (_ui.value.mode == Mode.ROOTED && !caps.gadgetModeUsable && !backup) Mode.NO_ROOT else _ui.value.mode
        _ui.value = _ui.value.copy(probing = false, caps = caps, hasBackup = backup, mode = mode)
    }

    fun setMode(m: Mode) { _ui.value = _ui.value.copy(mode = m) }

    fun setCdrom(v: Boolean) { _ui.value = _ui.value.copy(cdrom = v) }

    fun setManualSha(v: String) { _ui.value = _ui.value.copy(expectedSha = v.trim(), expectedSource = "entered by you", selected = null) }

    fun select(e: CatalogEntry) = viewModelScope.launch(Dispatchers.IO) {
        _ui.value = _ui.value.copy(selected = e, cdrom = e.defaultCdrom, error = "")
        if (e.sha256 != null) {
            _ui.value = _ui.value.copy(expectedSha = e.sha256, expectedSource = "pinned in catalog")
            return@launch
        }
        val sha = runCatching { fetchChecksum(e) }.getOrNull()
        _ui.value = if (sha != null) _ui.value.copy(expectedSha = sha, expectedSource = "vendor checksum file")
        else _ui.value.copy(expectedSha = "", expectedSource = "", error = "Could not fetch ${e.name}'s checksum. Enter the SHA-256 by hand.")
    }

    private fun fetchChecksum(e: CatalogEntry): String? {
        val c = URL(e.checksumUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        return c.inputStream.use { ImageVerifier.findSha256(it.bufferedReader().readText().take(1_000_000), e.isoFilename) }
    }

    /** Copies the picked document into the app's private image folder, hashing as it goes. */
    fun import(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "image.iso"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("iso", "img")) {
            _ui.value = _ui.value.copy(error = "Pick an .iso or .img file. Compressed images (.xz, .zip) must be decompressed first.")
            return@launch
        }
        val dir = GadgetService.imagesDir(ctx)
        dir.listFiles()?.forEach { it.delete() } // one image at a time
        val dest = File(dir, "exposed.$ext")
        _ui.value = _ui.value.copy(error = "", image = ImageState(displayName = name, importing = true))
        try {
            val (sha, n) = ctx.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { out -> ImageVerifier.copyAndHash(input, out) { p -> if (p % (64L shl 20) < (1 shl 20)) _ui.value = _ui.value.copy(image = _ui.value.image.copy(progress = p)) } }
            }
            dest.setReadOnly()
            _ui.value = _ui.value.copy(image = ImageState(dest, name, n, sha, n, false))
        } catch (e: Exception) {
            dest.delete()
            _ui.value = _ui.value.copy(image = ImageState(), error = "Import failed: ${e.message}")
        }
    }

    suspend fun clearError() = withContext(Dispatchers.Main) { _ui.value = _ui.value.copy(error = "") }
}
