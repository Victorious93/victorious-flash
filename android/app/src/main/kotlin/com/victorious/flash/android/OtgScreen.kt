package com.victorious.flash.android

import android.content.Intent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.victorious.flash.android.otg.*
import com.victorious.flash.android.ui.*

/** The exact word the user must type on the danger screen. */
const val ERASE_WORD = "ERASE"

fun otgConfirmOk(typed: String, ticked: Boolean) = ticked && typed.trim() == ERASE_WORD

@Composable
private fun OtgCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Panel, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(title, color = Electric, fontWeight = FontWeight.Bold, fontSize = 17.sp); Spacer(Modifier.height(6.dp)); content()
    }
}

@Composable
fun OtgCards(ui: UiState, vm: OtgViewModel = viewModel()) {
    val o by vm.ui.collectAsStateWithLifecycle()
    val w by UsbWriteService.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var danger by remember { mutableStateOf(false) }

    OtgCard("3. Plug in the USB stick (OTG)") {
        Text("Connect a USB flash drive through an OTG adapter. Only drives attached to this phone are listed.", fontSize = 13.sp)
        if (o.devices.isEmpty()) Text("No USB drive detected.", color = Electric)
        o.devices.forEach { (name, label) ->
            Row(Modifier.fillMaxWidth().selectable(o.selected == name, enabled = !w.running) { vm.select(name) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(o.selected == name, { vm.select(name) }, enabled = !w.running); Text(label)
            }
        }
        OutlinedButton(onClick = { vm.refresh() }, enabled = !w.running) { Text("Refresh") }
        if (o.probing) Text("Reading stick…")
        o.info?.let {
            Text("${it.vendor} ${it.product}".trim().ifEmpty { it.label }, fontWeight = FontWeight.Bold)
            Text("Capacity ${humanBytes(it.bytes)} · ${it.blockSize}-byte blocks", fontSize = 13.sp)
            if (ui.image.bytes > 0 && ui.image.bytes > it.bytes) Text("Too small: image is ${humanBytes(ui.image.bytes)}. Use a bigger stick.", color = Danger, fontWeight = FontWeight.Bold)
        }
        if (o.error.isNotEmpty()) Text(o.error, color = Danger)
    }

    OtgCard("4. Write to USB stick") {
        val info = o.info
        val fits = info != null && ui.image.bytes in 1..info.bytes
        val ready = ui.verified && ui.image.file != null && fits && !w.running
        if (!ui.verified) Text("Verify an image's SHA-256 in step 2 first.", fontSize = 13.sp)
        if (!w.running) Button(onClick = { danger = true }, enabled = ready, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)) { Text("Write to USB stick…") }
        if (w.running || w.phase.isNotEmpty() && w.outcome == UsbWriteService.Outcome.NONE) {
            Text(w.phase, color = Electric, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { if (w.total > 0) w.done.toFloat() / w.total else 0f }, Modifier.fillMaxWidth(), color = Electric)
            Text("${humanBytes(w.done)} / ${humanBytes(w.total)} · %.1f MB/s".format(w.bytesPerSec / 1e6) + (w.etaSec?.let { " · ETA %d:%02d".format(it / 60, it % 60) } ?: ""), fontSize = 13.sp)
            OutlinedButton(onClick = { ctx.startService(Intent(ctx, UsbWriteService::class.java).setAction(UsbWriteService.ACTION_CANCEL)) }) { Text("Cancel") }
        }
        when (w.outcome) {
            UsbWriteService.Outcome.VERIFIED -> Text("✔ Write verified", color = Electric, fontWeight = FontWeight.Black, fontSize = 20.sp)
            UsbWriteService.Outcome.FAILED -> Text("✘ Not verified", color = Danger, fontWeight = FontWeight.Black, fontSize = 20.sp)
            UsbWriteService.Outcome.CANCELLED -> Text("Cancelled", color = Danger, fontWeight = FontWeight.Bold)
            UsbWriteService.Outcome.NONE -> {}
        }
        if (w.message.isNotEmpty() && !w.running) Text(w.message, color = if (w.outcome == UsbWriteService.Outcome.VERIFIED) Electric else Danger)
    }

    val info = o.info
    if (danger && info != null) DangerScreen(info, ui.image.displayName, onCancel = { danger = false }) {
        danger = false
        UsbWriteService.reset()
        ctx.startForegroundService(Intent(ctx, UsbWriteService::class.java).setAction(UsbWriteService.ACTION_WRITE)
            .putExtra(UsbWriteService.EXTRA_DEVICE, info.deviceName).putExtra(UsbWriteService.EXTRA_PATH, ui.image.file?.path)
            .putExtra(UsbWriteService.EXTRA_SHA, ui.expectedSha).putExtra(UsbWriteService.EXTRA_CONFIRMED_BYTES, info.bytes))
    }
}

@Composable
private fun DangerScreen(info: OtgInfo, image: String, onCancel: () -> Unit, onConfirm: () -> Unit) = RedConfirmScreen(
    headline = "EVERYTHING on ${info.display} WILL BE ERASED.",
    body = "All files and partitions on this USB stick are overwritten with $image. This cannot be undone.",
    tickLabel = "I understand everything on this stick will be destroyed",
    confirmLabel = "ERASE AND WRITE", onCancel = onCancel, onConfirm = onConfirm)

/** The red full-screen danger confirm. The user must tick the box AND type [ERASE_WORD]. Shared by local and fleet-requested actions. */
@Composable
fun RedConfirmScreen(headline: String, body: String, tickLabel: String, confirmLabel: String, onCancel: () -> Unit, extra: String = "", onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var ticked by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFFB00020)).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("⚠ DANGER", color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
            if (extra.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(extra, color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
            Text(headline, color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Color.White)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().selectable(ticked) { ticked = !ticked }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ticked, { ticked = it }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color(0xFFB00020), uncheckedColor = Color.White))
                Text(tickLabel, color = Color.White)
            }
            OutlinedTextField(typed, { typed = it }, label = { Text("Type $ERASE_WORD to confirm", color = Color.White) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White))
            Spacer(Modifier.height(20.dp))
            Button(onClick = onConfirm, enabled = otgConfirmOk(typed, ticked), modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB00020))) { Text(confirmLabel, fontWeight = FontWeight.Black) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Color.White) }
        }
    }
}
