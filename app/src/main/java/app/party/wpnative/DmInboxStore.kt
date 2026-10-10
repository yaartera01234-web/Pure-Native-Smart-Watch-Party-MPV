package app.party.wpnative

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Original Smart Music ke `un` + `lrt` inbox model ka native equivalent.
 *
 * Firestore snapshot exact unread count deta hai; [readThrough] local read watermark hai jo
 * server write ke round-trip ke dauran badge ko dobara flash hone se rokta hai. State persist
 * hoti hai taa-ke Inbox/Calls khulte hi badge available ho, listener ka wait na karna pade.
 */
data class DmInboxState(
    val unread: Int = 0,
    val preview: String = "",
    val lastTs: Long = 0L,
    val outgoing: Boolean = false,
    val readThrough: Long = 0L
)

object DmInboxStore {
    private const val PREF = "wp_dm_inbox_state_v1"
    private const val REQUESTS = "_friend_requests"
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(ctx: Context, chatId: String): DmInboxState {
        val raw = prefs(ctx).getString(chatId, "").orEmpty()
        if (raw.isBlank()) return DmInboxState()
        return try {
            val o = JSONObject(raw)
            DmInboxState(
                unread = o.optInt("un", 0).coerceAtLeast(0),
                preview = o.optString("preview", ""),
                lastTs = o.optLong("lastTs", 0L),
                outgoing = o.optBoolean("out", false),
                readThrough = o.optLong("lrt", 0L)
            )
        } catch (_: Throwable) { DmInboxState() }
    }

    /** Exact live snapshot -> latest preview + per-friend unread number. */
    fun applySnapshot(ctx: Context, chatId: String, messages: List<ChatMsg>, me: String) {
        if (chatId.isBlank()) return
        val old = get(ctx, chatId)
        val visible = messages.asSequence().filter { !it.deleted && it.type != "call_signal" }
            .sortedByDescending { it.ts }.toList()
        val latest = visible.firstOrNull()
        val futureLimit = System.currentTimeMillis() + 60_000L
        val unread = visible.count { m ->
            m.from != me && m.type != "call" && !m.read &&
                m.ts > old.readThrough && m.ts <= futureLimit
        }
        val next = old.copy(
            unread = unread,
            preview = latest?.let(::preview).orEmpty(),
            lastTs = latest?.ts ?: 0L,
            outgoing = latest?.from == me
        )
        put(ctx, chatId, next)
    }

    /** Chat khulte hi Original v49: `un=0`, `lrt=max(now,lastMsgTs+1s)`. */
    fun markRead(ctx: Context, chatId: String, newestTs: Long = 0L) {
        if (chatId.isBlank()) return
        val old = get(ctx, chatId)
        val latest = maxOf(old.lastTs, newestTs)
        val through = maxOf(old.readThrough, System.currentTimeMillis(), latest + 1_000L)
        put(ctx, chatId, old.copy(unread = 0, readThrough = through))
    }

    fun forget(ctx: Context, chatId: String) {
        if (chatId.isBlank()) return
        prefs(ctx).edit().remove(chatId).apply()
        notifyChanged()
    }

    fun setRequestCount(ctx: Context, count: Int) {
        val safe = count.coerceAtLeast(0)
        if (prefs(ctx).getInt(REQUESTS, 0) == safe) return
        prefs(ctx).edit().putInt(REQUESTS, safe).apply()
        notifyChanged()
    }

    /** Original unreadTotal(): pending requests + har accepted friend ka `un`. */
    fun total(ctx: Context): Int = prefs(ctx).getInt(REQUESTS, 0).coerceAtLeast(0) +
        Friends.entries(ctx).sumOf { friend ->
            get(ctx, WpUser.friendChatId(ctx, friend.name, friend.code)).unread
        }

    fun observe(observer: () -> Unit) {
        observers += observer
        main.post(observer)
    }

    fun removeObserver(observer: () -> Unit) { observers -= observer }

    private fun put(ctx: Context, chatId: String, state: DmInboxState) {
        if (get(ctx, chatId) == state) return
        val o = JSONObject().apply {
            put("un", state.unread)
            put("preview", state.preview)
            put("lastTs", state.lastTs)
            put("out", state.outgoing)
            put("lrt", state.readThrough)
        }
        prefs(ctx).edit().putString(chatId, o.toString()).apply()
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post { observers.forEach { runCatching { it() } } }
    }

    private fun preview(m: ChatMsg): String = when (m.type) {
        "photo" -> "🖼️ Photo"
        "gif" -> "🎞️ GIF"
        "voice" -> "🎤 Voice message"
        "call" -> "☎ Voice call"
        else -> m.text.trim().replace(Regex("\\s+"), " ").take(90)
    }
}
