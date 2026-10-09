package app.party.wpnative

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Phone ka chhota cache: **har chat ke aakhri 120 messages** (JSON file).
 *
 * Fayda: chat turant khulti hai (Firebase ka intezar nahi), aur baar baar
 * wahi messages dobara download nahi hote -> reads bachti hain, koi hang nahi.
 */
object ChatCache {

    /**
     * Phone mein **har chat ke aakhri 120 messages** (Boss ka hukm).
     * 121waana purana message phone se bhi mit jata hai aur Firestore se bhi.
     */
    const val MAX = FirebaseChat.MSG_KEEP

    private fun file(ctx: Context, chatId: String): File =
        File(ctx.filesDir, "chat_" + chatId.replace(Regex("[^A-Za-z0-9_.\\-]"), "_") + ".json")

    fun save(ctx: Context, chatId: String, msgs: List<ChatMsg>) {
        val keep = if (msgs.size > MAX) msgs.takeLast(MAX) else msgs
        try {
            val arr = JSONArray()
            keep.forEach { arr.put(it.toJson()) }
            file(ctx, chatId).writeText(arr.toString())
        } catch (t: Throwable) {
            // cache fail ho to koi baat nahi — Firebase se aa jayega
        }
    }

    fun load(ctx: Context, chatId: String): MutableList<ChatMsg> {
        return try {
            val f = file(ctx, chatId)
            if (!f.exists()) return mutableListOf()
            val arr = JSONArray(f.readText())
            val out = mutableListOf<ChatMsg>()
            for (i in 0 until arr.length()) out.add(ChatMsg.fromJson(arr.getJSONObject(i)))
            out
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun clear(ctx: Context, chatId: String) {
        try { file(ctx, chatId).delete() } catch (t: Throwable) {}
    }
}
