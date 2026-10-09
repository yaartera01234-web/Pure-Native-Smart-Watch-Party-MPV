package app.party.wpnative

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Room ka chhota task sentinel.
 *
 * Home/minimize, rotation, DM/Inbox navigation aur network glitch par kuch nahi karta.
 * Sirf Android jab poori app task ko Recents se swipe-away bataye ([onTaskRemoved]),
 * us waqt wahi proper Party Leave chalata hai jo header ke 🚪 button se chalta hai.
 */
class PartyTaskService : Service() {

    companion object {
        @Volatile private var taskLeaveRunning = false

        fun start(ctx: Context) {
            try {
                ctx.startService(Intent(ctx.applicationContext, PartyTaskService::class.java))
            } catch (_: Throwable) { }
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(
                    Intent(ctx.applicationContext, PartyTaskService::class.java))
            } catch (_: Throwable) { }
        }

        /** Activity/service dono signal dein tab bhi MQTT Leave sirf ek baar chale. */
        @Synchronized
        fun leaveRemovedTask(ctx: Context) {
            if (taskLeaveRunning || !PartyTower.hasLiveSession()) return
            taskLeaveRunning = true
            val app = ctx.applicationContext
            MediaCache.deletePrefix(app, "party_")
            PartyTower.leave {
                taskLeaveRunning = false
                stop(app)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        leaveRemovedTask(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
