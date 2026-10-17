package com.victorious.flash.android.netboot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import com.victorious.flash.android.MainActivity
import com.victorious.flash.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** No-root mode: serves the verified image over HTTP on tethering/hotspot interfaces until stopped. */
class ServeService : Service() {
    data class State(val running: Boolean = false, val urls: List<Pair<LocalAddr, String>> = emptyList(), val efiUrls: List<String> = emptyList(), val efiProblem: String = "", val officialOffered: Boolean = false, val bytesServed: Long = 0, val lastRequest: String = "", val message: String = "")

    companion object {
        const val ACTION_START = "com.victorious.flash.android.SERVE_START"
        const val ACTION_STOP = "com.victorious.flash.android.SERVE_STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_OFFICIAL_URL = "official_url"
        const val PORT = 8080
        private const val CHANNEL = "serve"
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state
        fun urlPathFor(f: File) = "/ubuntu." + f.extension.lowercase().ifEmpty { "iso" }
    }

    private var server: IsoHttpServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_PATH), EfiBoot.officialUrlOrNull(intent.getStringExtra(EXTRA_OFFICIAL_URL)))
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(path: String?, officialUrl: String?) {
        startInForeground()
        server?.stop()
        val f = path?.let(::File)
        if (f == null || !f.isFile) { _state.value = State(message = "No verified image."); stopSelf(); return }
        val addrs = Tethering.current()
        if (addrs.isEmpty()) { _state.value = State(message = "No USB tethering / hotspot / Wi-Fi address found. Turn on USB tethering first, then press Start again."); stopSelf(); return }
        val urlPath = urlPathFor(f)
        // Lighter .efi path: Ubuntu's signed shim + GRUB + kernel + initrd, read straight out of the verified ISO.
        val (routes, plan) = EfiBoot.routes(f, urlPath, officialUrl)
        val srv = IsoHttpServer(routes, addrs.map(Tethering::toInet), PORT) { line -> _state.value = _state.value.copy(lastRequest = line, bytesServed = server?.bytesServed?.get() ?: 0) }
        try { srv.start() } catch (e: Exception) {
            _state.value = State(message = "Could not start server on port $PORT: ${e.message}"); stopSelf(); return
        }
        server = srv
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vflash:serve").apply { acquire(4 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vflash:serve").apply { acquire() }
        _state.value = State(running = true, urls = addrs.map { it to "http://${it.ip}:${srv.boundPort}$urlPath" },
            efiUrls = if (plan.usable) addrs.map { "http://${it.ip}:${srv.boundPort}${EfiBoot.SHIM}" } else emptyList(),
            efiProblem = if (plan.usable) "" else "This image has no Ubuntu-style EFI boot files (missing: ${plan.missing.joinToString()}). Use the raw-ISO URL.",
            officialOffered = plan.usable && officialUrl != null, message = "Serving ${f.length() shr 20} MiB read-only.")
    }

    override fun onDestroy() {
        server?.stop(); server = null
        runCatching { wake?.release() }; runCatching { wifi?.release() }
        _state.value = State(message = "Server stopped.")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_serve), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, ServeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_usb)
            .setContentTitle(getString(R.string.serve_notif_title))
            .setContentText(getString(R.string.serve_notif_text))
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.serve_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(2, n)
    }
}
