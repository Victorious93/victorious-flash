package com.victorious.flash.android.gadget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.victorious.flash.android.MainActivity
import com.victorious.flash.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/** Foreground service that owns the exposed state. Stopping it (or the app's task being removed) restores USB. */
class GadgetService : Service() {

    data class State(val exposed: Boolean = false, val busy: Boolean = false, val message: String = "")

    companion object {
        const val ACTION_EXPOSE = "com.victorious.flash.android.EXPOSE"
        const val ACTION_STOP = "com.victorious.flash.android.STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_CDROM = "cdrom"
        private const val CHANNEL = "gadget"
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        @Volatile var capabilities: Capabilities? = null

        fun backupDir(ctx: Context) = ctx.filesDir.canonicalFile.resolve("gadget-backup").path
        fun imagesDir(ctx: Context) = ctx.filesDir.canonicalFile.resolve("images").also { it.mkdirs() }
    }

    private val controller by lazy { GadgetController(SuRootShell(), backupDir(this)) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXPOSE -> {
                startInForeground()
                val path = intent.getStringExtra(EXTRA_PATH) ?: return stopNow("No image.")
                val cdrom = intent.getBooleanExtra(EXTRA_CDROM, false)
                val caps = capabilities ?: return stopNow("Run the capability check first.")
                _state.value = State(busy = true, message = "Exposing…")
                thread {
                    when (val r = controller.expose(caps, path, cdrom, listOf(imagesDir(this).path))) {
                        is GadgetController.Outcome.Ok -> _state.value = State(exposed = true, message = r.message)
                        is GadgetController.Outcome.Failed -> { _state.value = State(message = r.message); stopSelf() }
                    }
                }
            }
            ACTION_STOP -> restoreAndStop()
        }
        return START_NOT_STICKY
    }

    private fun stopNow(msg: String): Int { _state.value = State(message = msg); stopSelf(); return START_NOT_STICKY }

    private fun restoreAndStop() {
        val backend = capabilities?.backend ?: GadgetBackend.CONFIGFS
        _state.value = _state.value.copy(busy = true, message = "Restoring USB…")
        thread {
            val r = controller.restore(backend)
            _state.value = State(message = when (r) { is GadgetController.Outcome.Ok -> r.message; is GadgetController.Outcome.Failed -> r.message })
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { if (_state.value.exposed) restoreAndStop() }

    override fun onDestroy() {
        if (_state.value.exposed) {
            val backend = capabilities?.backend ?: GadgetBackend.CONFIGFS
            controller.restore(backend)
            _state.value = State(message = "USB restored.")
        }
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_gadget), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GadgetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_usb)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_restore), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }
}
