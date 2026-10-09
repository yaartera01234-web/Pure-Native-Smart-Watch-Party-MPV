package app.party.wpnative

import org.json.JSONObject

/**
 * Ek message — Firebase (Firestore) aur phone ke local cache dono mein yahi chalega.
 *
 * Firestore ke liye zaroori: no-arg constructor + har field ka default value
 * (is liye sab `var` hain, `val` nahi).
 *
 * type: "text" abhi — "photo" aur "voice" agle step mein (Cloudinary ke sath).
 */
data class ChatMsg(
    var id: String = "",
    var from: String = "",
    var text: String = "",
    var ts: Long = 0L,
    var read: Boolean = false,
    var replyName: String = "",
    var replyText: String = "",
    var type: String = "text",
    var deleted: Boolean = false      // mita hua message (doosre phone ko bhi pata chale is liye)
) {

    /** Firestore ke liye. */
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "from" to from,
        "text" to text,
        "ts" to ts,
        "read" to read,
        "replyName" to replyName,
        "replyText" to replyText,
        "type" to type,
        "deleted" to deleted
    )

    /** Phone ke cache ke liye. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("from", from)
        put("text", text)
        put("ts", ts)
        put("read", read)
        put("replyName", replyName)
        put("replyText", replyText)
        put("type", type)
        put("deleted", deleted)
    }

    companion object {
        /** Firestore document se. */
        fun fromMap(id: String, m: Map<String, Any?>): ChatMsg = ChatMsg(
            id = id,
            from = m["from"] as? String ?: "",
            text = m["text"] as? String ?: "",
            ts = when (val v = m["ts"]) {
                is Long -> v
                is Int -> v.toLong()
                is Double -> v.toLong()
                else -> 0L
            },
            read = m["read"] as? Boolean ?: false,
            replyName = m["replyName"] as? String ?: "",
            replyText = m["replyText"] as? String ?: "",
            type = m["type"] as? String ?: "text",
            deleted = m["deleted"] as? Boolean ?: false
        )

        /** Local cache (JSON) se. */
        fun fromJson(o: JSONObject): ChatMsg = ChatMsg(
            id = o.optString("id", ""),
            from = o.optString("from", ""),
            text = o.optString("text", ""),
            ts = o.optLong("ts", 0L),
            read = o.optBoolean("read", false),
            replyName = o.optString("replyName", ""),
            replyText = o.optString("replyText", ""),
            type = o.optString("type", "text"),
            deleted = o.optBoolean("deleted", false)
        )
    }
}
