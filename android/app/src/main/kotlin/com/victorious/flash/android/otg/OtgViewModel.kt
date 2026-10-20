package com.victorious.flash.android.otg

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class OtgUi(val devices: List<Pair<String, String>> = emptyList(), val selected: String? = null, val probing: Boolean = false,
                 val info: OtgInfo? = null, val error: String = "")

/** Lists attached USB mass-storage devices, asks for permission, and probes INQUIRY/capacity. Never writes. */
class OtgViewModel(app: Application) : AndroidViewModel(app) {
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _ui = MutableStateFlow(OtgUi())
    val ui: StateFlow<OtgUi> = _ui
    private val action = app.packageName + ".USB_PERMISSION"

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION") val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (i.action) {
                action -> if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) probe(d)
                          else _ui.value = _ui.value.copy(probing = false, error = "USB permission denied. Tap the stick again to retry.")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (d?.deviceName == _ui.value.selected) _ui.value = _ui.value.copy(selected = null, info = null, error = "Stick unplugged.")
                    refresh()
                }
                else -> refresh()
            }
        }
    }

    init {
        val f = IntentFilter(action).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(app, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() { _ui.value = _ui.value.copy(devices = OtgDevices.list(usb).map { it.deviceName to OtgDevices.label(it) }) }

    fun select(name: String) {
        val d = usb.deviceList[name] ?: run { refresh(); return }
        _ui.value = _ui.value.copy(selected = name, info = null, error = "", probing = true)
        if (usb.hasPermission(d)) probe(d) else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(getApplication(), 0, Intent(action).setPackage(getApplication<Application>().packageName), flags)
            usb.requestPermission(d, pi)
        }
    }

    private fun probe(d: UsbDevice) = viewModelScope.launch(Dispatchers.IO) {
        _ui.value = _ui.value.copy(probing = true, error = "")
        _ui.value = runCatching { probeOtg(usb, d) }.fold(
            { _ui.value.copy(probing = false, info = it, selected = d.deviceName) },
            { _ui.value.copy(probing = false, info = null, error = "Could not read the stick: ${it.message}") })
    }

    override fun onCleared() { runCatching { getApplication<Application>().unregisterReceiver(rx) } }
}
