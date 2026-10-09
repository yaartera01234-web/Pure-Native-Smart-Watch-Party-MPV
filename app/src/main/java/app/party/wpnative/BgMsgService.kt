package app.party.wpnative

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.firebase.firestore.ListenerRegistration

/**
 * App minimize / band (recents se swipe away) hone ke baad bhi DM notification.
 *
 * Ye wahi raasta hai jo Music Watch Party ka `BgNotifyService` use karta hai —
 * farq sirf itna: wahan andar ek CHHUPA WebView MQTT se judta tha, yahan
 * **seedha Firestore listener** hai. Koi WebView nahi, koi Firebase Cloud
 * Messaging nahi, koi server/relay nahi — is liye na card chahiye, na Apps Script.
 *
 * Service foreground rehti hai (START_STICKY), is liye Android isay
 * neend (Doze) mein bhi jaldi nahi marta.
 */
class BgMsgService : Service() {

    companion object {
        private const val NOTE_ID = 4242

        @Volatile var running = false

        /** Inbox / Join page se service chalu karo (pehle se chalu ho to dobara mat chhede). */
        fun start(ctx: Context) {
            if (running) return
            if (!FirebaseChat.isReady(ctx)) return
            try {
                val i = Intent(ctx, BgMsgService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (t: Throwable) { }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val regs = HashMap<String, ListenerRegistration>()
    private val lastTs = HashMap<String, Long>()     // chatId -> sabse naya ts jo dekh liya
    private var notePosted = false

    override fun onCreate() {
        super.onCreate()
        running = true
        try {
            startForeground(NOTE_ID, WpNotify.serviceNote(this))
            notePosted = true
        } catch (t: Throwable) {
            running = false
            stopSelf()
            return
        }
        /* "Messages on" wala note tang na kare -> 1-3 second baad chhup jaata hai,
           magar service chalti rehti hai (Android 13+ par note hatane se service nahi marti). */
        handler.postDelayed({ hideNote() }, 1300)
        handler.postDelayed({ hideNote() }, 2800)

        attach()
        handler.postDelayed(watchdog, 60000)
    }

    private fun hideNote() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
        } catch (t: Throwable) { }
    }

    /** Har dost ki chat par live listener — naya message aate hi notification. */
    private fun attach() {
        if (!FirebaseChat.isReady(this)) return
        val me = WpUser.me(this)
        val peers = Friends.all(this).filter { it != me }
        for (p in peers) {
            val chatId = WpUser.chatId(me, p)
            if (regs.containsKey(chatId)) continue
            try {
                val r = FirebaseChat.listenLast(this, chatId) { m ->
                    val prev = lastTs[chatId] ?: 0L
                    if (m.ts > prev) lastTs[chatId] = m.ts
                    if (prev == 0L) return@listenLast          // pehli dafa: purane messages chhodo
                    if (m.from == me) return@listenLast        // apna hi bheja hua
                    WpNotify.post(this, p, m.text, chatId)
                }
                if (r != null) regs[chatId] = r
            } catch (t: Throwable) { }
        }
        // hataye gaye doston ke listener band kar do
        val valid = peers.map { WpUser.chatId(me, it) }.toSet()
        val it = regs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in valid) {
                try { e.value.remove() } catch (t: Throwable) { }
                it.remove()
                lastTs.remove(e.key)
            }
        }
    }

    /** Har minute: naye dost jude / listener toote to dobara jodo. */
    private val watchdog = object : Runnable {
        override fun run() {
            try { attach() } catch (t: Throwable) { }
            handler.postDelayed(this, 60000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!notePosted) {
            try {
                startForeground(NOTE_ID, WpNotify.serviceNote(this))
                notePosted = true
            } catch (t: Throwable) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        try { attach() } catch (t: Throwable) { }
        return START_STICKY
    }

    /** Recents se swipe away karne par bhi service chalti rahe. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 2000)
        } catch (t: Throwable) { }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { handler.removeCallbacksAndMessages(null) } catch (t: Throwable) { }
        for (r in regs.values) { try { r.remove() } catch (t: Throwable) { } }
        regs.clear()
        super.onDestroy()
    }
}
