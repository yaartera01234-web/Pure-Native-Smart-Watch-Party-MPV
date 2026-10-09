package app.party.wpnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.lang.ref.WeakReference

/** Lock-screen/background controls for the same live MPV core (never a second player). */
class PartyPlayerService : Service() {
    companion object {
        private const val CHANNEL = "party_player"
        private const val NOTIFICATION = 4703
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"
        const val ACTION_TOGGLE = "app.party.wpnative.player.TOGGLE"
        const val ACTION_BACK = "app.party.wpnative.player.BACK"
        const val ACTION_FORWARD = "app.party.wpnative.player.FORWARD"
        const val ACTION_STOP = "app.party.wpnative.player.STOP"
        private const val ACTION_UPDATE = "app.party.wpnative.player.UPDATE"

        @Volatile private var receiver: WeakReference<(String) -> Unit>? = null

        fun bind(callback: (String) -> Unit) { receiver = WeakReference(callback) }
        fun unbind(callback: (String) -> Unit) {
            if (receiver?.get() === callback) receiver = null
        }

        fun update(context: Context, title: String, playing: Boolean) {
            val intent = Intent(context.applicationContext, PartyPlayerService::class.java)
                .setAction(ACTION_UPDATE).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PLAYING, playing)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (_: Throwable) { }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context.applicationContext, PartyPlayerService::class.java)) }
            catch (_: Throwable) { }
        }
    }

    private var title = "Watch Party"
    private var playing = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Party player",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Native MPV playback controls"
                setShowBadge(false)
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE, null -> {
                title = intent?.getStringExtra(EXTRA_TITLE)?.take(80) ?: title
                playing = intent?.getBooleanExtra(EXTRA_PLAYING, playing) ?: playing
            }
            ACTION_STOP -> {
                receiver?.get()?.invoke(ACTION_STOP)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE, ACTION_BACK, ACTION_FORWARD -> receiver?.get()?.invoke(intent.action!!)
        }
        startForeground(NOTIFICATION, notification())
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, PartyRoomActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable())
        fun service(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, PartyPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable())
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val prev = Notification.Action.Builder(R.drawable.ic_notif, "Back 10", service(ACTION_BACK, 2)).build()
        val toggle = Notification.Action.Builder(R.drawable.ic_notif, if (playing) "Pause" else "Play", service(ACTION_TOGGLE, 3)).build()
        val next = Notification.Action.Builder(R.drawable.ic_notif, "Forward 10", service(ACTION_FORWARD, 4)).build()
        val stop = Notification.Action.Builder(R.drawable.ic_notif, "Stop", service(ACTION_STOP, 5)).build()
        return builder.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title.ifBlank { "Watch Party" })
            .setContentText("Native MPV · Party sync")
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(playing).setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(prev).addAction(toggle).addAction(next).addAction(stop)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun immutable(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Recents swipe is a real Party Leave. Do not let a media FGS keep stale audio alive.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
