package app.party.wpnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Saari notifications ek jagah se (Music Watch Party ke NotifHub jaisa).
 *
 *  - DM notification + WhatsApp jaisa **Reply** button (RemoteInput)
 *  - Reply -> ReplyReceiver -> seedha Firestore (koi WebView nahi, to 100% pakka)
 *  - Background service ka chhupi hui (IMPORTANCE_MIN) note
 */
object WpNotify {

    const val CH_DM = "wp_messages"
    const val CH_BG = "wp_bg"

    const val REPLY_KEY = "wp_reply_text"
    const val ACTION_REPLY = "app.party.wpnative.REPLY"

    private var lastKey = ""
    private var lastTs = 0L

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_DM) == null) {
                val dm = NotificationChannel(CH_DM, "Messages", NotificationManager.IMPORTANCE_HIGH)
                dm.enableVibration(true)
                dm.description = "Dost ke messages"
                dm.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                nm.createNotificationChannel(dm)
            }
            if (nm.getNotificationChannel(CH_BG) == null) {
                val bg = NotificationChannel(CH_BG, "Background (messages on)",
                    NotificationManager.IMPORTANCE_MIN)
                bg.setShowBadge(false)
                bg.enableLights(false)
                bg.enableVibration(false)
                bg.setSound(null, null)
                bg.lockscreenVisibility = Notification.VISIBILITY_SECRET
                bg.description = "App band hone pe bhi messages aate rahen"
                nm.createNotificationChannel(bg)
            }
        } catch (t: Throwable) { }
    }

    /** Notification id: har chat ka apna (usi chat ki purani notification update ho). */
    fun id(chatId: String): Int = 6000 + (Math.abs(chatId.hashCode()) % 900)

    fun cancel(ctx: Context, chatId: String) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id(chatId))
        } catch (t: Throwable) { }
    }

    /** Naye message ki notification (Reply button ke saath). */
    fun post(ctx: Context, from: String, text: String, chatId: String, friendCode: String = "") {
        synchronized(this) {
            val key = "$from|$text"
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastTs < 8000) return   // duplicate na aaye
            lastKey = key
            lastTs = now
        }
        if (WpActive.chatId == chatId || WpActive.peer == from) return // yahi stable chat khuli hai
        try {
            ensureChannels(ctx)
            val nid = id(chatId)

            val openIntent = Intent(ctx, ChatActivity::class.java)
                .putExtra("name", from)
                .putExtra("friendCode", friendCode)
                .putExtra("chatId", chatId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val imm = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val openPi = PendingIntent.getActivity(ctx, nid, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or imm)

            val replyPi = replyIntent(ctx, from, chatId, nid)

            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_DM)
                    else Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(from)
                .setContentText(text)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openPi)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)

            try {
                val ri = RemoteInput.Builder(REPLY_KEY).setLabel("Jawab likho…").build()
                val act = Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notif), "Reply", replyPi
                )
                    .addRemoteInput(ri)
                    .setAllowGeneratedReplies(true)
                    .build()
                b.addAction(act)
                b.setStyle(Notification.MessagingStyle(WpUser.me(ctx))
                    .addMessage(text, System.currentTimeMillis(), from))
            } catch (t: Throwable) { }

            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(nid, b.build())
        } catch (t: Throwable) { }
    }

    /**
     * Reply ka PendingIntent. Android 12+ par FLAG_MUTABLE laazmi hai
     * (IMMUTABLE ho to system likha hua text nahi de paata) — warna reply kaam nahi karta.
     */
    private fun replyIntent(ctx: Context, peer: String, chatId: String, nid: Int): PendingIntent {
        val i = Intent(ctx, ReplyReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra("peer", peer)
            .putExtra("chatId", chatId)
        val flags = if (Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, nid, i, flags)
    }

    /** Foreground service ka silent note (visibility ke liye chahiye, phir chhup jata hai). */
    fun serviceNote(ctx: Context): Notification {
        ensureChannels(ctx)
        val openIntent = Intent(ctx, InboxActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val imm = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(ctx, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or imm)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_BG)
                else Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("💬 Messages on")
            .setContentText("Naya message aane pe notification aayegi")
            .setOngoing(false)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(pi)
        return b.build()
    }
}
