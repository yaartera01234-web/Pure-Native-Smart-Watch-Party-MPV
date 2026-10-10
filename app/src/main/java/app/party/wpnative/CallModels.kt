package app.party.wpnative

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

enum class CallPhase {
    IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, RECONNECTING, ENDED
}

data class CallSnapshot(
    val callId: String = "",
    val chatId: String = "",
    val peerName: String = "",
    val peerCode: String = "",
    val outgoing: Boolean = false,
    val phase: CallPhase = CallPhase.IDLE,
    val startedAt: Long = 0L,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val status: String = ""
) {
    val live: Boolean get() = phase !in listOf(CallPhase.IDLE, CallPhase.ENDED)
}

/** In-process state observed by the full call screen and history page. */
object CallState {
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(CallSnapshot) -> Unit>()
    @Volatile private var value = CallSnapshot()

    fun current(): CallSnapshot = value
    fun active(): Boolean = value.live

    fun update(next: CallSnapshot) {
        value = next
        main.post { observers.forEach { runCatching { it(next) } } }
    }

    fun mutate(block: (CallSnapshot) -> CallSnapshot) = update(block(value))

    fun observe(observer: (CallSnapshot) -> Unit) {
        observers += observer
        main.post { observer(value) }
    }

    fun remove(observer: (CallSnapshot) -> Unit) { observers -= observer }
    fun clear() = update(CallSnapshot())
}

data class CallRecord(
    val id: String,
    val peerName: String,
    val peerCode: String,
    val chatId: String,
    val outgoing: Boolean,
    val outcome: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Int
) {
    val missed: Boolean get() = outcome == "missed"

    fun json() = JSONObject().apply {
        put("id", id); put("peerName", peerName); put("peerCode", peerCode); put("chatId", chatId)
        put("outgoing", outgoing); put("outcome", outcome); put("startedAt", startedAt)
        put("endedAt", endedAt); put("durationSec", durationSec)
    }

    companion object {
        fun from(o: JSONObject) = CallRecord(
            id = o.optString("id"), peerName = o.optString("peerName", "Dost"),
            peerCode = o.optString("peerCode"), chatId = o.optString("chatId"),
            outgoing = o.optBoolean("outgoing"), outcome = o.optString("outcome", "ended"),
            startedAt = o.optLong("startedAt"), endedAt = o.optLong("endedAt"),
            durationSec = o.optInt("durationSec")
        )
    }
}

/** Small local history, matching Original's latest-100 behavior. */
object CallStore {
    private const val PREF = "wp_call_history"
    private const val KEY = "rows_v1"

    @Synchronized fun all(ctx: Context): List<CallRecord> = read(ctx)
        .sortedByDescending { it.endedAt }.take(100)

    @Synchronized fun add(ctx: Context, record: CallRecord) {
        if (record.id.isBlank()) return
        val rows = read(ctx).filterNot { it.id == record.id }.toMutableList()
        rows.add(record)
        write(ctx, rows.sortedByDescending { it.endedAt }.take(100))
    }

    @Synchronized fun remove(ctx: Context, id: String) = write(ctx, read(ctx).filterNot { it.id == id })
    @Synchronized fun clear(ctx: Context) = write(ctx, emptyList())
    @Synchronized fun clearMissed(ctx: Context) = write(ctx, read(ctx).filterNot { it.missed })

    private fun read(ctx: Context): List<CallRecord> = try {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList { for (i in 0 until arr.length()) add(CallRecord.from(arr.getJSONObject(i))) }
    } catch (_: Throwable) { emptyList() }

    private fun write(ctx: Context, rows: List<CallRecord>) {
        val arr = JSONArray(); rows.forEach { arr.put(it.json()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

data class PendingCall(
    val callId: String,
    val chatId: String,
    val peerName: String,
    val peerCode: String,
    val at: Long
) {
    fun json() = JSONObject().apply {
        put("callId", callId); put("chatId", chatId); put("peerName", peerName)
        put("peerCode", peerCode); put("at", at)
    }

    companion object {
        fun from(raw: String): PendingCall? = try {
            val o = JSONObject(raw)
            PendingCall(o.getString("callId"), o.getString("chatId"),
                o.optString("peerName", "Dost"), o.getString("peerCode"), o.getLong("at"))
                .takeIf { it.callId.isNotBlank() && it.peerCode.isNotBlank() }
        } catch (_: Throwable) { null }
    }
}

object PendingCallStore {
    private const val PREF = "wp_pending_call"
    private const val KEY = "incoming"

    fun save(ctx: Context, value: PendingCall) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, value.json().toString()).apply()
    }

    fun get(ctx: Context): PendingCall? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val value = PendingCall.from(p.getString(KEY, "").orEmpty()) ?: return null
        // Keep enough time to turn an interrupted process' ringing invite into durable missed
        // history on next launch. The controller still enforces the real 30-second ring window.
        if (System.currentTimeMillis() - value.at > 24L * 60L * 60L * 1000L) { clear(ctx); return null }
        return value
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

internal fun newCallId(): String = "c" + UUID.randomUUID().toString().replace("-", "").take(20)
