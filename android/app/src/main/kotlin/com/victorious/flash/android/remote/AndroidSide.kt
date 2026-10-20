package com.victorious.flash.android.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import com.victorious.flash.android.R
import com.victorious.flash.android.gadget.GadgetService
import com.victorious.flash.android.netboot.ServeService
import com.victorious.flash.android.otg.OtgDevices
import com.victorious.flash.android.otg.UsbWriteService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Persistent switch (default OFF) and pinned-controller list. */
class RemoteSettingsStore(ctx: Context) : RemoteSettings {
    private val p = ctx.applicationContext.getSharedPreferences("fleet_remote", Context.MODE_PRIVATE)
    override val enabled get() = p.getBoolean("enabled", false)
    override val controllers get() = PinnedController.listFromJson(p.getString("controllers", "[]"))
    fun setEnabled(v: Boolean) { p.edit().putBoolean("enabled", v).apply(); changed.value++ }
    fun setControllers(l: List<PinnedController>) { p.edit().putString("controllers", PinnedController.listToJson(l)).apply(); changed.value++ }
    val nodeRef: String get() = p.getString("nodeRef", null) ?: ("vfa-" + UUID.randomUUID().toString().take(13)).also { p.edit().putString("nodeRef", it).apply() }
    companion object { val changed = MutableStateFlow(0) }
}

/** The image the user verified in the app (path + matching SHA-256). Remote actions can only use this image. */
object VerifiedImageStore {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("verified_image", Context.MODE_PRIVATE)
    fun save(ctx: Context, file: File?, sha: String?, name: String) {
        val e = p(ctx).edit()
        if (file != null && sha != null) e.putString("path", file.path).putString("sha", sha).putString("name", name) else e.clear()
        e.apply()
    }
    data class Img(val file: File, val sha: String, val name: String)
    fun get(ctx: Context): Img? { val q = p(ctx); val f = q.getString("path", null)?.let(::File)?.takeIf { it.isFile } ?: return null
        return Img(f, q.getString("sha", null) ?: return null, q.getString("name", "") ?: "") }
}

data class PendingConfirmation(val id: String, val actionRef: String, val params: JSONObject, val requestId: String, val controller: String, val createdMs: Long) {
    fun expired(now: Long) = now - createdMs > 5 * 60_000L
}

object PendingConfirmations {
    private val map = ConcurrentHashMap<String, PendingConfirmation>()
    private val _latest = MutableStateFlow<PendingConfirmation?>(null)
    val latest: StateFlow<PendingConfirmation?> = _latest
    fun add(p: PendingConfirmation) { map[p.id] = p; _latest.value = p }
    /** One-shot: an approval or rejection consumes the request. */
    fun take(id: String): PendingConfirmation? = map.remove(id)?.also { if (_latest.value?.id == id) _latest.value = null }
    fun peek(id: String) = map[id]
}

/** Resolves the OS-reported calling UID to packages and their current signing-cert SHA-256s. */
object CallerResolver {
    fun resolve(ctx: Context, uid: Int): CallerIdentity {
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid)?.toList().orEmpty()
        return CallerIdentity(uid, pkgs.associateWith { certs(pm, it) })
    }

    @Suppress("DEPRECATION")
    private fun certs(pm: PackageManager, pkg: String): List<String> = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray() ?: si.apkContentsSigners
        } else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        // With multiple signers every one must be pinned, so only expose a cert set when there is exactly one.
        if (sigs == null || sigs.size != 1) emptyList() else listOf(MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()).joinToString("") { "%02x".format(it) })
    }.getOrDefault(emptyList())
}

class AndroidNodeActions(private val ctx: Context, private val settings: RemoteSettingsStore) : NodeActions {
    override val nodeRef get() = settings.nodeRef

    override fun status(): JSONObject {
        val img = VerifiedImageStore.get(ctx)
        val srv = ServeService.state.value; val g = GadgetService.state.value; val w = UsbWriteService.state.value
        return JSONObject().put("nodeRef", nodeRef).put("app", "victorious-flash-android")
            .put("verifiedImage", img?.let { JSONObject().put("name", it.name).put("bytes", it.file.length()).put("sha256", it.sha) } ?: JSONObject.NULL)
            .put("netboot", JSONObject().put("running", srv.running).put("urls", srv.urls.map { it.second }).put("bytesServed", srv.bytesServed).put("message", srv.message))
            .put("gadget", JSONObject().put("exposed", g.exposed).put("busy", g.busy).put("usable", GadgetService.capabilities?.gadgetModeUsable ?: false))
            .put("otg", JSONObject().put("running", w.running).put("phase", w.phase).put("done", w.done).put("total", w.total).put("outcome", w.outcome.name.lowercase())
                .put("attachedSticks", OtgDevices.list(ctx.getSystemService(Context.USB_SERVICE) as UsbManager).map { JSONObject().put("deviceName", it.deviceName).put("label", OtgDevices.label(it)) }))
    }

    override fun unavailableReason(actionRef: String): String? = when (actionRef) {
        ActionRegistry.SERVE_START -> if (VerifiedImageStore.get(ctx) == null) "no verified image on the phone" else if (GadgetService.state.value.exposed) "gadget mode is active" else null
        ActionRegistry.OTG_WRITE -> when {
            !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) -> "phone has no USB host (OTG) support"
            VerifiedImageStore.get(ctx) == null -> "no verified image on the phone"
            UsbWriteService.state.value.running -> "a write is already running"
            else -> null
        }
        ActionRegistry.GADGET_EXPOSE -> when {
            GadgetService.capabilities?.gadgetModeUsable != true -> "rooted gadget mode is not available (run the check in Rooted mode first)"
            VerifiedImageStore.get(ctx) == null -> "no verified image on the phone"
            else -> null
        }
        else -> null
    }

    override fun serveStart(params: JSONObject): JSONObject {
        val img = VerifiedImageStore.get(ctx) ?: throw RemoteError(ErrorCode.CAPABILITY_OFF, "no verified image")
        ctx.startForegroundService(Intent(ctx, ServeService::class.java).setAction(ServeService.ACTION_START).putExtra(ServeService.EXTRA_PATH, img.file.path))
        return JSONObject().put("started", true).put("note", "poll status.get for URLs; USB tethering must be on")
    }

    override fun serveStop(params: JSONObject): JSONObject {
        ctx.startService(Intent(ctx, ServeService::class.java).setAction(ServeService.ACTION_STOP))
        return JSONObject().put("stopping", true)
    }

    override fun requestConfirmation(actionRef: String, params: JSONObject, requestId: String, controller: PinnedController): String {
        when (actionRef) {
            ActionRegistry.OTG_WRITE -> {
                val dn = params.optString("deviceName", "")
                if (dn.isEmpty()) throw RemoteError(ErrorCode.INVALID_PARAMETERS, "otg.write needs params.deviceName (see status.get otg.attachedSticks)")
                val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
                // Only USB mass-storage devices attached to THIS phone can ever be targets.
                if (OtgDevices.list(usb).none { it.deviceName == dn }) throw RemoteError(ErrorCode.NOT_FOUND, "no USB stick '$dn' is attached to the phone")
            }
            ActionRegistry.GADGET_EXPOSE -> if (params.has("cdrom") && params.opt("cdrom") !is Boolean) throw RemoteError(ErrorCode.INVALID_PARAMETERS, "cdrom must be a boolean")
            else -> throw RemoteError(ErrorCode.INTERNAL, "not a destructive action")
        }
        val id = UUID.randomUUID().toString()
        PendingConfirmations.add(PendingConfirmation(id, actionRef, JSONObject(params.toString()), requestId, controller.packageName, System.currentTimeMillis()))
        notify(id, actionRef)
        return id
    }

    private fun notify(id: String, actionRef: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fleet confirmations", NotificationManager.IMPORTANCE_HIGH))
        val i = Intent(ctx, RemoteConfirmActivity::class.java).putExtra(RemoteConfirmActivity.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val what = if (actionRef == ActionRegistry.OTG_WRITE) "erase a USB stick" else "switch USB to disk mode"
        val n = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_usb).setContentTitle("Fleet controller wants to $what")
            .setContentText("Nothing happens unless you confirm on the phone. Tap to review.").setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build()
        runCatching { nm.notify(id.hashCode(), n) }
    }

    companion object { const val CHANNEL = "fleet_confirm" }
}
