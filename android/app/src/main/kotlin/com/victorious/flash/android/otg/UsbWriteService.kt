package com.victorious.flash.android.otg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.victorious.flash.android.MainActivity
import com.victorious.flash.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service that writes one verified image to one user-confirmed USB stick, then reads it back to verify. */
class UsbWriteService : Service() {
    enum class Outcome { NONE, VERIFIED, FAILED, CANCELLED }
    data class State(val running: Boolean = false, val phase: String = "", val done: Long = 0, val total: Long = 0,
                     val bytesPerSec: Double = 0.0, val etaSec: Long? = null, val outcome: Outcome = Outcome.NONE, val message: String = "")

    companion object {
        const val ACTION_WRITE = "com.victorious.flash.android.OTG_WRITE"
        const val ACTION_CANCEL = "com.victorious.flash.android.OTG_CANCEL"
        const val EXTRA_DEVICE = "device"; const val EXTRA_PATH = "path"; const val EXTRA_SHA = "sha"; const val EXTRA_CONFIRMED_BYTES = "confirmed_bytes"
        private const val CHANNEL = "otg"; private const val NOTIF = 3
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state
        fun reset() { if (!_state.value.running) _state.value = State() }
    }

    private val cancel = AtomicBoolean(false)
    private val unplugged = AtomicBoolean(false)
    private var target: String? = null
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private val detach = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION") val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (d?.deviceName == target) { unplugged.set(true); cancel.set(true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WRITE -> if (worker == null) start(intent) else Unit
            ACTION_CANCEL -> cancel.set(true)
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        startInForeground("Preparing…", 0, 0)
        val name = i.getStringExtra(EXTRA_DEVICE); val path = i.getStringExtra(EXTRA_PATH); val sha = i.getStringExtra(EXTRA_SHA)
        val confirmed = i.getLongExtra(EXTRA_CONFIRMED_BYTES, -1)
        target = name
        ContextCompat.registerReceiver(this, detach, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vflash:otg").apply { acquire(6 * 60 * 60 * 1000L) }
        _state.value = State(running = true, phase = "Preparing")
        worker = Thread({ finish(runCatching { work(name, path, sha, confirmed) }) }, "otg-writer").also { it.start() }
    }

    private fun work(name: String?, path: String?, sha: String?, confirmed: Long): DiskWriter.Result {
        val f = path?.let(::File)?.takeIf { it.isFile } ?: throw IOException("The verified image is missing")
        if (sha.isNullOrBlank()) throw IOException("No expected SHA-256")
        val m = getSystemService(USB_SERVICE) as UsbManager
        val d = m.deviceList.values.firstOrNull { it.deviceName == name } ?: throw IOException("The USB stick is no longer attached")
        return UsbBulkTransport.open(m, d).use { t ->
            val dev = ScsiBlockDevice(t).init()
            // The stick must be the one the user confirmed on the danger screen.
            if (dev.bytes != confirmed) throw IOException("The attached stick (${humanBytes(dev.bytes)}) is not the one you confirmed. Nothing was written.")
            val meter = RateMeter()
            var last = 0L
            DiskWriter(dev).run({ f.inputStream() }, f.length(), sha, { cancel.get() }) { p ->
                val (bps, eta) = meter.update(p.phase, p.done, p.total)
                val label = when (p.phase) { DiskWriter.Phase.HASHING -> "Checking image SHA-256"; DiskWriter.Phase.WRITING -> "Writing"; DiskWriter.Phase.SYNCING -> "Flushing stick cache"; DiskWriter.Phase.VERIFYING -> "Reading back to verify" }
                _state.value = _state.value.copy(phase = label, done = p.done, total = p.total, bytesPerSec = bps, etaSec = eta)
                val now = System.currentTimeMillis()
                if (now - last > 1000) { last = now; startInForeground(label, p.done, p.total) }
            }
        }
    }

    private fun finish(r: kotlin.Result<DiskWriter.Result>) {
        val s = r.fold({ res ->
            when (res) {
                is DiskWriter.Result.Verified -> State(outcome = Outcome.VERIFIED, message = "Write verified: read-back SHA-256 matches (${res.sha256}). Safe to unplug.")
                is DiskWriter.Result.SourceMismatch -> State(outcome = Outcome.FAILED, message = "Image SHA-256 does not match the expected hash. Blocked before writing.\nexpected ${res.expected}\nactual   ${res.actual}")
                is DiskWriter.Result.TooSmall -> State(outcome = Outcome.FAILED, message = "Stick too small: ${humanBytes(res.deviceBytes)} < image ${humanBytes(res.imageBytes)}. Nothing was written.")
                is DiskWriter.Result.VerifyMismatch -> State(outcome = Outcome.FAILED, message = "VERIFY FAILED: the data read back from the stick does not match the image. Do not use this stick to boot. The stick may be fake/failing; try another.")
            }
        }, { e ->
            when {
                unplugged.get() -> State(outcome = Outcome.FAILED, message = "The stick was unplugged during the operation. Its contents are now incomplete; write it again.")
                e is WriteCancelled -> State(outcome = Outcome.CANCELLED, message = "Cancelled. If writing had started, the stick is incomplete and won't boot.")
                else -> State(outcome = Outcome.FAILED, message = "Write failed: ${e.message ?: e.javaClass.simpleName}")
            }
        })
        _state.value = s
        stopSelf()
    }

    override fun onDestroy() {
        cancel.set(true)
        runCatching { unregisterReceiver(detach) }
        runCatching { wake?.release() }
        if (_state.value.running) _state.value = State(outcome = Outcome.FAILED, message = "Writer stopped.")
        super.onDestroy()
    }

    private fun startInForeground(text: String, done: Long, total: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB stick writer", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_usb).setContentTitle("Writing USB stick")
            .setContentText(text).setProgress(100, pct, total == 0L).setOngoing(true).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF, n)
    }
}
