package app.party.wpnative

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Abhi kaun si chat khuli hai — uski notification nahi dikhenge (chat khud dikhata hai). */
object WpActive {
    @Volatile var peer: String? = null
}

/**
 * Firebase Cloud Messaging (FCM) — sirf tab kaam aata hai jab koi server/relay
 * push bheje. Abhi notifications **BgMsgService** (Firestore listener) se aa rahi
 * hain, is liye ye service mostly soyi rehti hai — future ke liye rakhi gayi hai.
 */
class WpMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FirebaseChat.saveToken(this, WpUser.me(this), token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        super.onMessageReceived(msg)
        val d = msg.data
        val from = d["from"] ?: return
        val body = d["text"] ?: d["body"] ?: return
        val chatId = d["chatId"] ?: WpUser.chatId(WpUser.me(this), from)
        WpNotify.post(this, from, body, chatId)
    }
}

/**
 * Notification ke "Reply" se likha gaya jawab — **seedha Firestore** mein.
 * (Music Watch Party mein ye WebView ke JS se hota tha, is liye kabhi fail ho jata tha.
 *  Yahan koi WebView nahi — reply direct save hota hai, 100% pakka.)
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context?, intent: Intent?) {
        if (ctx == null || intent == null) return
        if (WpNotify.ACTION_REPLY != intent.action) return
        val peer = intent.getStringExtra("peer") ?: return
        val chatId = intent.getStringExtra("chatId") ?: WpUser.chatId(WpUser.me(ctx), peer)
        val text = try {
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(WpNotify.REPLY_KEY)?.toString()?.trim()
        } catch (t: Throwable) { null }
        if (text.isNullOrEmpty()) return

        val me = WpUser.me(ctx)
        val async = goAsync()
        FirebaseChat.send(
            ctx, chatId,
            ChatMsg(from = me, text = text, ts = System.currentTimeMillis(), type = "text")
        )
        // notification hata do (abhi ya thodi der baad — send async hai)
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(
                WpNotify.id(chatId))
        } catch (t: Throwable) { }
        async?.finish()
    }
}
