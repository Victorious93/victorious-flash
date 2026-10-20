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
import com.victorious.flash.android.netboot.ServeService
import androidx.compose.foundation.text.selection.SelectionContainer

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

    fun serve(path: String, officialUrl: String?) {
        startForegroundService(Intent(this, ServeService::class.java).setAction(ServeService.ACTION_START).putExtra(ServeService.EXTRA_PATH, path)
            .putExtra(ServeService.EXTRA_OFFICIAL_URL, officialUrl))
    }

    fun stopServe() { startService(Intent(this, ServeService::class.java).setAction(ServeService.ACTION_STOP)) }

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
    val srv by ServeService.state.collectAsStateWithLifecycle()
    val act = androidx.compose.ui.platform.LocalContext.current as MainActivity
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column { Text("Victorious Flash", fontWeight = FontWeight.Black, fontSize = 22.sp); Text(when (ui.mode) { Mode.ROOTED -> "Phone as bootable USB (root)"; Mode.NO_ROOT -> "Network boot over USB tethering (no root)"; Mode.OTG -> "Write to USB stick over OTG (no root)" }, color = Electric) }
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Danger.copy(alpha = 0.22f), RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text("⚠ DISK WIPE RISK — read before installing", color = Danger, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Text(stringResource(R.string.install_wipe_warning))
        }

        Card("Mode") {
            Text(if (ui.suHint) "Root (su) detected on this phone." else "No root detected. No-root mode is the one for you.", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().selectable(ui.mode == Mode.ROOTED, enabled = !svc.exposed && !srv.running) { vm.setMode(Mode.ROOTED) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(ui.mode == Mode.ROOTED, { vm.setMode(Mode.ROOTED) }, enabled = !svc.exposed && !srv.running)
                Text("Rooted: phone becomes a USB drive (works with any PC that boots USB)")
            }
            Row(Modifier.fillMaxWidth().selectable(ui.mode == Mode.NO_ROOT, enabled = !svc.exposed && !srv.running) { vm.setMode(Mode.NO_ROOT) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(ui.mode == Mode.NO_ROOT, { vm.setMode(Mode.NO_ROOT) }, enabled = !svc.exposed && !srv.running)
                Text("No root: UEFI HTTP Boot over USB tethering (only some PCs)")
            }
            Row(Modifier.fillMaxWidth().selectable(ui.mode == Mode.OTG, enabled = !svc.exposed && !srv.running) { vm.setMode(Mode.OTG) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(ui.mode == Mode.OTG, { vm.setMode(Mode.OTG) }, enabled = !svc.exposed && !srv.running)
                Text("No root: write to a USB stick plugged into this phone (OTG), verified")
            }
        }

        if (ui.mode == Mode.ROOTED) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Danger.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text("⚠ Read this first", color = Danger, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.warning_long))
        } else if (ui.mode == Mode.NO_ROOT) Card("How no-root mode works") { Text(stringResource(R.string.noroot_intro)) }
        else Card("How Write to USB stick works") { Text(stringResource(R.string.otg_intro)) }

        if (ui.mode == Mode.ROOTED) Card("1. Can this phone do it?") {
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
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ui.image.importing && !svc.exposed && !srv.running) { Text("Pick ISO/IMG…") }
            if (ui.image.importing) Text("Copying + hashing ${ui.image.displayName}: ${ui.image.progress shr 20} MiB")
            ui.image.sha256?.let {
                Text("${ui.image.displayName} · ${ui.image.bytes shr 20} MiB", fontSize = 13.sp)
                Text("SHA-256 $it", fontSize = 11.sp)
                Check(ui.verified, if (ui.verified) "Matches expected hash" else "Does NOT match (or no expected hash). Sharing is blocked.")
            }
        }

        if (ui.mode == Mode.NO_ROOT) NoRootCards(ui, srv, act)
        if (ui.mode == Mode.OTG) OtgCards(ui)
        if (ui.mode == Mode.ROOTED) Card("3. Expose to the PC (read-only)") {
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
        com.victorious.flash.android.remote.RemoteSettingsCard()
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
private fun Step(n: Int, text: String) { Row(Modifier.padding(vertical = 2.dp)) { Text("$n.", color = Electric, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp)); Text(text) } }

@Composable
private fun NoRootCards(ui: UiState, srv: ServeService.State, act: MainActivity) {
    Card("3. Share over USB tethering") {
        Step(1, "Connect the phone to the PC with a USB data cable.")
        Step(2, "On the phone: Settings → Network & internet → Hotspot & tethering → turn on USB tethering. (Name varies: \"Mobile Hotspot and Tethering\" on Samsung.) Mobile data can stay off.")
        Step(3, "Come back here and press Start server. The app shows the exact URL to type.")
        val ready = ui.verified && ui.image.file != null
        if (!srv.running) {
            Button(onClick = { ui.image.file?.let { act.serve(it.path, ui.selected?.takeIf { ui.expectedSource != "entered by you" }?.isoUrl) } }, enabled = ready) { Text("Start server") }
            if (!ready) Text("Verify an image in step 2 first.", fontSize = 13.sp)
        } else {
            Text("SERVING (read-only). Type ONE of these URLs in the PC firmware:", color = Electric, fontWeight = FontWeight.Bold)
            if (srv.efiUrls.isNotEmpty()) {
                Text("A) Boot loader URL (.efi). Lighter; required for Dell. Recommended:", fontWeight = FontWeight.Bold)
                srv.efiUrls.forEach { SelectionContainer { Text(it, color = Electric, fontWeight = FontWeight.Black, fontSize = 18.sp) } }
                Text(if (srv.officialOffered) "The PC menu offers: ISO from this phone (offline), or ISO from releases.ubuntu.com (uses the phone's mobile data). Both load the whole ISO into PC RAM." else "The PC menu loads the ISO from this phone into PC RAM (offline).", fontSize = 12.sp)
            } else if (srv.efiProblem.isNotEmpty()) Text(srv.efiProblem, fontSize = 12.sp)
            Text("B) Whole-ISO URL (firmware loads the ISO as a RAM disk):", fontWeight = FontWeight.Bold)
            srv.urls.forEach { (a, url) ->
                SelectionContainer { Text(url, color = Electric, fontWeight = FontWeight.Black, fontSize = 18.sp) }
                Text("via ${a.iface} (" + when (a.kind) { com.victorious.flash.android.netboot.LocalAddr.Kind.USB_TETHER -> "USB tethering"; com.victorious.flash.android.netboot.LocalAddr.Kind.HOTSPOT -> "Wi-Fi hotspot"; else -> "other network" } + ")", fontSize = 12.sp)
            }
            Text("Sent: ${srv.bytesServed shr 20} MiB", fontSize = 13.sp)
            if (srv.lastRequest.isNotEmpty()) Text("Last request: ${srv.lastRequest}", fontSize = 11.sp)
            OutlinedButton(onClick = { act.stopServe() }) { Text("Stop server") }
        }
        if (srv.message.isNotEmpty()) Text(srv.message)
    }
    Card("4. On the PC: UEFI HTTP Boot") {
        Step(1, "Power on and enter firmware setup (usually F2, F10, F1, Del or Esc).")
        Step(2, "Make sure Boot mode is UEFI. Enable \"Network stack\" / \"IPv4 HTTP support\" / \"HTTP Boot\".")
        Step(3, "Find \"HTTP Boot Configuration\" (often under Device Manager / Network). If it lists a USB network device (the phone), select it. If no device appears, this PC can't use the phone's USB network — stop here.")
        Step(4, "Set IPv4 and enter Boot URI A (…/boot/bootx64.efi) exactly as shown above. If that fails and your firmware boots ISO files, try URL B (…/ubuntu.iso). Save.")
        Step(5, "Save & exit, then pick the new \"UEFI HTTP\" entry from the boot menu (F12/F9/F11/Esc).")
        Step(6, "With URL A, a GRUB menu appears in seconds, then Ubuntu downloads the ISO into RAM (minutes). With URL B, the firmware downloads the whole ISO first. Then choose Try or Install.")
    }
    Card("Dell PCs (BIOS \"HTTPs Boot\")") {
        Step(1, "Press F2 at the Dell logo. Under Connection (or Integrated Devices), enable \"UEFI Network Stack\".")
        Step(2, "Open \"HTTP(s) Boot\", enable it, choose Manual Mode.")
        Step(3, "Enter Boot URL A exactly (it must end in .efi): http://…/boot/bootx64.efi. Dell shows no error for a bad URL, so check every character.")
        Step(4, "Apply, exit, press F12 and choose \"UEFI HTTPs Boot\". If Dell asks for a CA certificate, that only applies to https:// URLs.")
        Step(5, "If the phone isn't listed as a network device, or Dell says \"Failed to find IP address\", this Dell can't use the phone's USB network. Use Rooted mode or a USB stick.")
    }
    Card("Compatibility — honest notes") {
        Text("Works only if ALL of these are true:", fontWeight = FontWeight.Bold)
        Text("• PC firmware supports UEFI HTTP Boot with a manual URL (UEFI 2.5+; common on PCs from ~2017 on, but often hidden or missing on consumer boards).")
        Text("• The firmware has a driver for the phone's USB network (RNDIS on older Android, NCM on newer). Most firmwares only drive built-in Ethernet or specific USB-Ethernet dongles. This is the most common failure.")
        Text("• Plain http:// is allowed. Some firmwares are HTTPS-only (e.g. recent ThinkPad custom HTTPS boot).")
        Text("• Dell client BIOS Manual Mode accepts only .efi URLs: use URL A. URL B (.iso) needs firmware with RAM-disk HTTP boot.")
        Text("• URL A boots Ubuntu's own Microsoft-signed shim and GRUB, so Secure Boot can stay on, BUT only if the firmware trusts the \"Microsoft 3rd-party UEFI CA\". Some PCs (Secured-core, some Lenovo/Dell) ship with it off. Enable it in the Secure Boot settings, or turn Secure Boot off temporarily.")
        Text("• After GRUB, Ubuntu's own kernel must bring up the phone's USB network (ip=dhcp) to download the ISO. Not yet confirmed for every phone.")
        Text("• The PC has enough RAM: the whole ISO is held in memory. Ubuntu Server (~3.2 GB) needs roughly 6 GB+, Ubuntu Desktop (~6 GB) roughly 12–16 GB+. Too little RAM fails with an out-of-resources error.")
        Text("• Secure Boot may still apply: Ubuntu's ISO is signed, but firmware policy varies.")
        Text("If it doesn't work: use Rooted mode (if your phone is rooted) or a USB stick. This mode has not been confirmed on real PCs yet.", color = Electric)
    }
}

@Composable
private fun stringResource(id: Int) = androidx.compose.ui.res.stringResource(id)
