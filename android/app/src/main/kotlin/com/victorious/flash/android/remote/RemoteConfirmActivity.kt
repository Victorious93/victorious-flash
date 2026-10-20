package com.victorious.flash.android.remote

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.victorious.flash.android.RedConfirmScreen
import com.victorious.flash.android.gadget.GadgetService
import com.victorious.flash.android.otg.OtgInfo
import com.victorious.flash.android.otg.UsbWriteService
import com.victorious.flash.android.otg.probeOtg
import com.victorious.flash.android.ui.Navy
import com.victorious.flash.android.ui.VictoriousTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-phone approval for a fleet-requested destructive action. Uses the same red tick + type-ERASE screen as local use. */
class RemoteConfirmActivity : ComponentActivity() {
    companion object { const val EXTRA_ID = "confirmation_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        setContent { VictoriousTheme { Surface(Modifier.fillMaxSize(), color = Navy) { Screen(id) } } }
    }

    @Composable
    private fun Screen(id: String) {
        val p = remember { PendingConfirmations.peek(id)?.takeIf { !it.expired(System.currentTimeMillis()) } }
        var info by remember { mutableStateOf<OtgInfo?>(null) }
        var error by remember { mutableStateOf(if (p == null) "This request has expired or was already handled. Nothing was done." else "") }
        val img = remember { VerifiedImageStore.get(this) }
        LaunchedEffect(p) {
            if (p?.actionRef == ActionRegistry.OTG_WRITE) {
                val usb = getSystemService(USB_SERVICE) as UsbManager
                val d = usb.deviceList[p.params.optString("deviceName")]
                error = when {
                    img == null -> "No verified image on the phone. Nothing was done."
                    d == null -> "That USB stick is no longer attached. Nothing was done."
                    !usb.hasPermission(d) -> "Open Victorious Flash → Write to USB stick and tap the stick once to grant USB permission, then ask the controller again. Nothing was done."
                    else -> runCatching { info = withContext(Dispatchers.IO) { probeOtg(usb, d) }; "" }.getOrElse { "Could not read the stick: ${it.message}" }
                }
                if (info != null && img != null && img.file.length() > info!!.bytes) { error = "Stick too small for the image. Nothing was done."; info = null }
            } else if (p != null && img == null) error = "No verified image on the phone. Nothing was done."
        }
        val reject = { PendingConfirmations.take(id); finish() }
        Column(Modifier.padding(24.dp)) {
            if (error.isNotEmpty()) { Text(error); Button(onClick = reject) { Text("Close") } }
        }
        if (p == null || error.isNotEmpty() || img == null) return
        val from = "Requested remotely by ${p.controller}. Only you can approve it, here on the phone."
        when (p.actionRef) {
            ActionRegistry.OTG_WRITE -> info?.let { inf ->
                RedConfirmScreen("EVERYTHING on ${inf.display} WILL BE ERASED.",
                    "All files and partitions on this USB stick (plugged into this phone) are overwritten with ${img.name}. This cannot be undone. Your PC's disks are not touched.",
                    "I understand everything on this stick will be destroyed", "ERASE AND WRITE", onCancel = reject, extra = from) {
                    if (PendingConfirmations.take(id) != null) {
                        UsbWriteService.reset()
                        startForegroundService(Intent(this, UsbWriteService::class.java).setAction(UsbWriteService.ACTION_WRITE)
                            .putExtra(UsbWriteService.EXTRA_DEVICE, inf.deviceName).putExtra(UsbWriteService.EXTRA_PATH, img.file.path)
                            .putExtra(UsbWriteService.EXTRA_SHA, img.sha).putExtra(UsbWriteService.EXTRA_CONFIRMED_BYTES, inf.bytes))
                    }
                    finish()
                }
            }
            ActionRegistry.GADGET_EXPOSE -> RedConfirmScreen("This phone's USB will switch to disk mode.",
                "The phone will appear to the connected PC as a read-only drive holding ${img.name}. USB, ADB and MTP stay off until you stop it. A PC that boots from it may let you wipe its disks later; that step is always yours.",
                "I understand and want to expose this image", "EXPOSE IMAGE", onCancel = reject, extra = from) {
                if (PendingConfirmations.take(id) != null)
                    startForegroundService(Intent(this, GadgetService::class.java).setAction(GadgetService.ACTION_EXPOSE)
                        .putExtra(GadgetService.EXTRA_PATH, img.file.path).putExtra(GadgetService.EXTRA_CDROM, p.params.optBoolean("cdrom", false)))
                finish()
            }
        }
    }
}
