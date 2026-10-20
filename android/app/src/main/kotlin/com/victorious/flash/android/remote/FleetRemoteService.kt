package com.victorious.flash.android.remote

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger

/**
 * EXTERNAL_NODE binding: JSON over a Messenger. Request: Message(what=MSG_REQUEST, data["json"]=envelope, replyTo=messenger).
 * Reply: Message(what=MSG_RESPONSE, data["json"]=response). The caller is identified by Message.sendingUid, which the
 * framework fills from Binder.getCallingUid() inside Messenger.send; it is then checked against the pinned package+cert pairs.
 */
class FleetRemoteService : Service() {
    companion object {
        const val MSG_REQUEST = 1; const val MSG_RESPONSE = 2; const val KEY_JSON = "json"
        private val nonces by lazy { NonceCache(clock = System::currentTimeMillis) }
    }

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("fleet-remote").apply { start() }
        val settings = RemoteSettingsStore(this)
        val dispatcher = RemoteDispatcher(settings, AndroidNodeActions(this, settings), nonces, System::currentTimeMillis)
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_REQUEST) return
                val reply = msg.replyTo ?: return
                val raw = msg.data?.getString(KEY_JSON) ?: "{}"
                val caller = CallerResolver.resolve(this@FleetRemoteService, msg.sendingUid)
                val out = dispatcher.handle(raw, caller)
                runCatching { reply.send(Message.obtain(null, MSG_RESPONSE).apply { data = Bundle().apply { putString(KEY_JSON, out) } }) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
}
