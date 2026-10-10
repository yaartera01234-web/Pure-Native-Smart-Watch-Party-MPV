package app.party.wpnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
        const val ACTION_PREVIOUS = "app.party.wpnative.player.PREVIOUS"
        const val ACTION_PLAY = "app.party.wpnative.player.PLAY"
        const val ACTION_PAUSE = "app.party.wpnative.player.PAUSE"
        const val ACTION_TOGGLE = "app.party.wpnative.player.TOGGLE"
        const val ACTION_NEXT = "app.party.wpnative.player.NEXT"
        const val ACTION_BACK = "app.party.wpnative.player.BACK"
        const val ACTION_FORWARD = "app.party.wpnative.player.FORWARD"
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
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession(this, "WatchPartyPlayer").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = dispatch(ACTION_PLAY)
                override fun onPause() = dispatch(ACTION_PAUSE)
                override fun onSkipToPrevious() = dispatch(ACTION_PREVIOUS)
                override fun onSkipToNext() = dispatch(ACTION_NEXT)
                override fun onRewind() = dispatch(ACTION_BACK)
                override fun onFastForward() = dispatch(ACTION_FORWARD)
            })
            isActive = true
        }
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
            ACTION_PREVIOUS, ACTION_PLAY, ACTION_PAUSE, ACTION_TOGGLE, ACTION_NEXT,
            ACTION_BACK, ACTION_FORWARD -> dispatch(intent.action!!)
        }
        syncMediaSession()
        startForeground(NOTIFICATION, notification())
        return START_NOT_STICKY
    }

    private fun dispatch(action: String) {
        receiver?.get()?.invoke(action)
    }

    private fun syncMediaSession() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD
        mediaSession.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
            .build())
        mediaSession.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { "Watch Party" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Watch Party · Room sync")
            .build())
        mediaSession.isActive = true
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
        // Expanded panel keeps ten-second seeking as well. Compact/lock-screen controls
        // are the standard previous-song, play/pause and next-song trio.
        val previous = Notification.Action.Builder(android.R.drawable.ic_media_previous,
            "Previous song", service(ACTION_PREVIOUS, 2)).build()
        val back = Notification.Action.Builder(android.R.drawable.ic_media_rew,
            "Back 10 seconds", service(ACTION_BACK, 3)).build()
        val toggle = Notification.Action.Builder(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Pause" else "Play", service(ACTION_TOGGLE, 4)).build()
        val forward = Notification.Action.Builder(android.R.drawable.ic_media_ff,
            "Forward 10 seconds", service(ACTION_FORWARD, 5)).build()
        val next = Notification.Action.Builder(android.R.drawable.ic_media_next,
            "Next song", service(ACTION_NEXT, 6)).build()
        return builder.setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title.ifBlank { "Watch Party" })
            .setContentText("Native MPV · Party sync")
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(playing).setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(previous).addAction(back).addAction(toggle).addAction(forward).addAction(next)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 2, 4))
            .build()
    }

    private fun immutable(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Recents swipe is a real Party Leave. Do not let a media FGS keep stale audio alive.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
