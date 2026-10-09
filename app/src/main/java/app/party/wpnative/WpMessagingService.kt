package app.party.wpnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Abhi kaun si chat khuli hai — uski notification nahi dikhenge (Firestore khud dikhayega). */
object WpActive {
    @Volatile var peer: String? = null
}

/**
 * Push notification (WhatsApp jaisi):
 *  - app background/band ho to bhi message ki khabar
 *  - notification se hi **seedha jawab** (RemoteInput) — likha hua reply Firestore mein jata hai
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
        val body = d["text"] ?: return
        val chatId = d["chatId"] ?: WpUser.chatId(WpUser.me(this), from)

        // Yahi chat khuli hai to notification bekaar hai
        if (WpActive.peer == from) return

        showMsgNotification(this, from, body, chatId)
    }

    companion object {
        const val CH_ID = "wp_messages"
        const val KEY_REPLY = "wp_reply_text"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_ID) != null) return
            val ch = NotificationChannel(CH_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Naye messages"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(ch)
        }

        @Suppress("DEPRECATION")
        fun showMsgNotification(ctx: Context, from: String, body: String, chatId: String) {
            ensureChannel(ctx)
            val id = chatId.hashCode()

            // Notification dabane se wahi chat khule
            val openIntent = Intent(ctx, ChatActivity::class.java)
                .putExtra("name", from)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val imm = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val openPi = PendingIntent.getActivity(ctx, id, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or imm)

            // Seedha jawab (WhatsApp wala "Reply" button)
            val replyIntent = Intent(ctx, ReplyReceiver::class.java)
                .putExtra("peer", from)
                .putExtra("chatId", chatId)
            val mut = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val replyPi = PendingIntent.getBroadcast(ctx, id, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or mut)

            val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Jawab likho...").build()
            val replyAction = Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_notif), "Reply", replyPi
            ).addRemoteInput(remoteInput).build()

            val style = Notification.MessagingStyle(WpUser.me(ctx))
                .addMessage(body, System.currentTimeMillis(), from)

            // Android 7 (API 24/25) mein channel wala constructor nahi hota
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_ID)
                          else Notification.Builder(ctx)
            val n = builder
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(from)
                .setContentText(body)
                .setStyle(style)
                .setContentIntent(openPi)
                .addAction(replyAction)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        }
    }
}

/**
 * Notification ke "Reply" se likha gaya jawab — seedha Firestore mein bhej deta hai
 * (koi screen kholne ki zarurat nahi).
 */
class ReplyReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(WpMessagingService.KEY_REPLY)
            ?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val peer = intent.getStringExtra("peer") ?: return
        val chatId = intent.getStringExtra("chatId") ?: WpUser.chatId(WpUser.me(ctx), peer)
        val me = WpUser.me(ctx)

        val m = ChatMsg(from = me, text = text, ts = System.currentTimeMillis(), type = "text")
        FirebaseChat.send(ctx, chatId, m)

        // notification hata do aur doosre ko bhi khabar kar do
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(chatId.hashCode())
        Push.notifyPeer(ctx, peer, me, text)
    }
}
