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
    var media: String = "",        // photo/voice ka asli maal (base64) — SIRF Firestore ke liye
    var dur: Int = 0,              // voice: kitne second
    var wave: String = "",         // voice: har 100ms ki awaaz "12,40,80,..."
    var deleted: Boolean = false,     // mita hua message (doosre phone ko bhi pata chale is liye)
    var reactions: MutableMap<String, String> = linkedMapOf() // stable actor-code -> emoji
) {

    /** Firestore ke liye. (media sirf tab, jab ho — document chhota rahe) */
    fun toMap(): Map<String, Any> {
        val m = linkedMapOf<String, Any>(
            "id" to id,
            "from" to from,
            "text" to text,
            "ts" to ts,
            "read" to read,
            "replyName" to replyName,
            "replyText" to replyText,
            "type" to type,
            "dur" to dur,
            "wave" to wave,
            "deleted" to deleted,
            "reactions" to reactions
        )
        if (media.isNotBlank()) m["media"] = media
        return m
    }

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
        put("dur", dur)
        put("wave", wave)
        put("deleted", deleted)
        put("reactions", JSONObject().apply { reactions.forEach { (actor, emoji) -> put(actor, emoji) } })
        // URL-backed keyboard GIF ko cache mein rakho; photo/voice/base64 ko nahi.
        if (type == "gif" && media.startsWith("url:")) put("media", media)
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
            media = m["media"] as? String ?: "",
            dur = (m["dur"] as? Long)?.toInt() ?: 0,
            wave = m["wave"] as? String ?: "",
            deleted = m["deleted"] as? Boolean ?: false,
            reactions = linkedMapOf<String, String>().apply {
                (m["reactions"] as? Map<*, *>)?.forEach { (actor, emoji) ->
                    if (actor is String && emoji is String && actor.isNotBlank() && emoji.isNotBlank()) {
                        put(actor, emoji)
                    }
                }
            }
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
            media = o.optString("media", ""),
            dur = o.optInt("dur", 0),
            wave = o.optString("wave", ""),
            deleted = o.optBoolean("deleted", false),
            reactions = jsonReactions(o.optJSONObject("reactions"))
        )

        private fun jsonReactions(o: JSONObject?): MutableMap<String, String> =
            linkedMapOf<String, String>().apply {
                if (o == null) return@apply
                val keys = o.keys()
                while (keys.hasNext()) {
                    val actor = keys.next()
                    val emoji = o.optString(actor, "")
                    if (actor.isNotBlank() && emoji.isNotBlank()) put(actor, emoji)
                }
            }
    }
}
