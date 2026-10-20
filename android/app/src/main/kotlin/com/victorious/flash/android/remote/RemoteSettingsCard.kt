package com.victorious.flash.android.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.victorious.flash.android.ui.*

@Composable
fun RemoteSettingsCard() {
    val ctx = LocalContext.current
    val store = remember { RemoteSettingsStore(ctx) }
    val tick by RemoteSettingsStore.changed.collectAsStateWithLifecycle()
    val enabled = remember(tick) { store.enabled }
    val pins = remember(tick) { store.controllers }
    var pkg by remember { mutableStateOf("") }
    var cert by remember { mutableStateOf("") }
    val pending by PendingConfirmations.latest.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Panel, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text("Fleet remote control", color = Electric, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow fleet remote control", Modifier.weight(1f))
            Switch(enabled, { store.setEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Electric))
        }
        Text("Off by default. Only the controller apps pinned below (package + signing-cert SHA-256) can talk to this phone. Wipes and disk mode always need you to confirm here on the phone.", fontSize = 12.sp)
        Text("Node: ${store.nodeRef}", fontSize = 12.sp)
        if (enabled && pins.none { it.isComplete }) Text("No controller pinned: every request is refused.", color = Electric, fontSize = 13.sp)
        pins.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${DefaultPins.labelFor(p)?.let { "$it\n" } ?: ""}${p.packageName}\n${p.certSha256.ifBlank { "(cert not set — inactive)" }}", Modifier.weight(1f), fontSize = 11.sp)
                TextButton(onClick = { store.setControllers(pins.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
            }
        }
        OutlinedTextField(pkg, { pkg = it.trim() }, label = { Text("Controller package") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cert, { cert = it.trim() }, label = { Text("Controller signing cert SHA-256") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val valid = PinnedController.PKG.matches(pkg) && (cert.isEmpty() || PinnedController.normalizeCert(cert) != null)
        OutlinedButton(onClick = { store.setControllers(pins + PinnedController(pkg, PinnedController.normalizeCert(cert) ?: "")); pkg = ""; cert = "" }, enabled = valid) { Text("Pin controller") }
        pending?.let { Text("Pending request from ${it.controller}: ${it.actionRef}. Tap its notification to review.", color = Danger, fontSize = 13.sp) }
    }
}
