package com.victorious.flash.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.victorious.flash.android.gadget.GadgetBackend
import com.victorious.flash.android.gadget.GadgetService
import com.victorious.flash.android.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictoriousTheme { Surface(Modifier.fillMaxSize(), color = Navy) { Screen(vm) } } }
    }

    fun expose(path: String, cdrom: Boolean) {
        startForegroundService(Intent(this, GadgetService::class.java).setAction(GadgetService.ACTION_EXPOSE)
            .putExtra(GadgetService.EXTRA_PATH, path).putExtra(GadgetService.EXTRA_CDROM, cdrom))
    }

    fun stop() { startService(Intent(this, GadgetService::class.java).setAction(GadgetService.ACTION_STOP)) }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Panel, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(title, color = Electric, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Check(ok: Boolean?, label: String) {
    Text((when (ok) { true -> "✔ "; false -> "✘ "; null -> "• " }) + label, color = when (ok) { true -> Electric; false -> Danger; null -> MaterialTheme.colorScheme.onSurface })
}

@Composable
private fun Screen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val svc by GadgetService.state.collectAsStateWithLifecycle()
    val act = androidx.compose.ui.platform.LocalContext.current as MainActivity
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column { Text("Victorious Flash", fontWeight = FontWeight.Black, fontSize = 22.sp); Text("Phone as bootable USB", color = Electric) }
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Danger.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text("⚠ Read this first", color = Danger, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.warning_long))
        }

        Card("1. Can this phone do it?") {
            val c = ui.caps
            Check(c?.rooted, "Root (su) available")
            Check(c?.let { it.backend != GadgetBackend.NONE }, "USB gadget backend: " + (c?.backend?.name?.lowercase() ?: "not checked"))
            Check(c?.massStorageSupported, "mass_storage function in the kernel")
            Check(c?.let { it.udcs.isNotEmpty() }, "USB controller (UDC): " + (c?.udcs?.joinToString().orEmpty().ifEmpty { "–" }))
            c?.notes?.forEach { Text(it, fontSize = 13.sp) }
            c?.let { Text(if (it.gadgetModeUsable) "Works on this phone." else "Doesn't work on this phone (see above).", color = if (it.gadgetModeUsable) Electric else Danger, fontWeight = FontWeight.Bold) }
            Button(onClick = { vm.probe() }, enabled = !ui.probing) { Text(if (ui.probing) "Checking…" else "Run check") }
            if (ui.hasBackup && !svc.exposed) OutlinedButton(onClick = { act.stop() }) { Text("Restore USB (leftover from last session)") }
        }

        Card("2. Choose the image") {
            Text("Catalog (boot-tested type):", fontSize = 13.sp)
            ui.catalog.forEach { e ->
                Row(Modifier.fillMaxWidth().selectable(ui.selected?.id == e.id) { vm.select(e) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(ui.selected?.id == e.id, { vm.select(e) })
                    Text("${e.name} ${e.version}  [${e.gadget.joinToString { it.wire }}]")
                }
            }
            ui.selected?.let { Text("Download ${it.isoFilename} from ${it.isoUrl} on any device, then pick it below.", fontSize = 13.sp) }
            OutlinedTextField(ui.expectedSha, vm::setManualSha, label = { Text("Expected SHA-256") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (ui.expectedSource.isNotEmpty()) Text("Hash source: ${ui.expectedSource}", fontSize = 12.sp)
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ui.image.importing && !svc.exposed) { Text("Pick ISO/IMG…") }
            if (ui.image.importing) Text("Copying + hashing ${ui.image.displayName}: ${ui.image.progress shr 20} MiB")
            ui.image.sha256?.let {
                Text("${ui.image.displayName} · ${ui.image.bytes shr 20} MiB", fontSize = 13.sp)
                Text("SHA-256 $it", fontSize = 11.sp)
                Check(ui.verified, if (ui.verified) "Matches expected hash" else "Does NOT match (or no expected hash). Exposing is blocked.")
            }
        }

        Card("3. Expose to the PC (read-only)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!ui.cdrom, { vm.setCdrom(false) }); Text("USB disk (hybrid ISOs, default)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(ui.cdrom, { vm.setCdrom(true) }); Text("CD-ROM (ISO-only images)")
            }
            val ready = ui.caps?.gadgetModeUsable == true && ui.verified && ui.image.file != null && !svc.busy
            if (!svc.exposed) {
                Button(onClick = { confirm = true }, enabled = ready, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Expose image") }
            } else {
                Text("EXPOSED. USB, ADB and MTP are off until you stop.", color = Danger, fontWeight = FontWeight.Bold)
                Button(onClick = { act.stop() }, enabled = !svc.busy) { Text("Stop & restore USB") }
            }
            if (svc.message.isNotEmpty()) Text(svc.message)
        }
        if (ui.error.isNotEmpty()) Text(ui.error, color = Danger)
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Switch USB to disk mode?", color = Danger) },
        text = { Text(stringResource(R.string.warning_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; ui.image.file?.let { act.expose(it.path, ui.cdrom) } }) { Text("Expose", color = Danger) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun stringResource(id: Int) = androidx.compose.ui.res.stringResource(id)
