package app.party.wpnative

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.max

/**
 * Native port of party-final1.html's Room sleep timer.
 *
 * The choice is local, but expiry pauses locally and publishes the normal user pause command so
 * every active Room member stops at the same retained position. A wall-clock deadline plus a
 * one-shot main-thread callback gives the Original's timeout + visibility catch-up behavior.
 */
object PartySleepTimer {
    val choicesMinutes = listOf(20, 40, 60)

    private const val PREFS = "wp_native"
    private const val KEY_AT = "party_sleep_at"
    private const val KEY_MINS = "party_sleep_mins"
    private const val KEY_PENDING = "party_sleep_pending"
    private const val KEY_ROOM = "party_sleep_room"

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val fire = Runnable { fireDue() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun currentRoom(context: Context): String =
        prefs(context).getString("room", "").orEmpty().trim().lowercase()

    /** Restore/schedule after Activity recreation; an overdue timer fires immediately. */
    fun arm(context: Context) {
        appContext = context.applicationContext
        main.removeCallbacks(fire)
        val p = prefs(context)
        val at = p.getLong(KEY_AT, 0L)
        if (at <= 0L) return
        val timerRoom = p.getString(KEY_ROOM, "").orEmpty()
        if (timerRoom.isNotBlank() && timerRoom != currentRoom(context)) {
            cancel(context)
            return
        }
        val delay = at - System.currentTimeMillis()
        if (delay <= 0L) fireDue() else main.postDelayed(fire, delay)
    }

    fun set(context: Context, minutes: Int) {
        require(minutes in choicesMinutes) { "Unsupported sleep timer: $minutes" }
        appContext = context.applicationContext
        val at = System.currentTimeMillis() + minutes * 60_000L
        prefs(context).edit()
            .putLong(KEY_AT, at)
            .putInt(KEY_MINS, minutes)
            .putBoolean(KEY_PENDING, false)
            .putString(KEY_ROOM, currentRoom(context))
            .apply()
        arm(context)
    }

    fun cancel(context: Context) {
        appContext = context.applicationContext
        main.removeCallbacks(fire)
        prefs(context).edit()
            .remove(KEY_AT)
            .remove(KEY_MINS)
            .remove(KEY_PENDING)
            .remove(KEY_ROOM)
            .apply()
    }

    fun remainingMinutes(context: Context): Int {
        val left = prefs(context).getLong(KEY_AT, 0L) - System.currentTimeMillis()
        return if (left <= 0L) 0 else max(1, ((left + 59_999L) / 60_000L).toInt())
    }

    fun menuLabel(context: Context): String {
        val left = remainingMinutes(context)
        return if (left > 0) "⌛  Sleep Timer — $left min baqi" else "⌛  Sleep Timer"
    }

    fun isActive(context: Context): Boolean = remainingMinutes(context) > 0

    /** Original's mqtt-down retry: reconnect/Room restore republishes the same pause. */
    fun retryPending(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        if (!p.getBoolean(KEY_PENDING, false)) return
        val timerRoom = p.getString(KEY_ROOM, "").orEmpty()
        if (timerRoom.isNotBlank() && timerRoom != currentRoom(context)) {
            cancel(context)
            return
        }
        PartyRoomRoute.fireSleepTimer()
    }

    /** Called only after the Room pause has reached the selected Party Tower. */
    fun markDelivered(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).remove(KEY_ROOM).apply()
    }

    private fun fireDue() {
        val context = appContext ?: return
        val p = prefs(context)
        val at = p.getLong(KEY_AT, 0L)
        if (at <= 0L) return
        val delay = at - System.currentTimeMillis()
        if (delay > 0L) {
            main.postDelayed(fire, delay)
            return
        }
        // Clear active label before firing, but retain a retry marker until the tower receives it.
        p.edit().remove(KEY_AT).remove(KEY_MINS).putBoolean(KEY_PENDING, true).apply()
        val routed = PartyRoomRoute.fireSleepTimer()
        if (!routed && !PartyTower.hasLiveSession()) markDelivered(context)
    }
}
