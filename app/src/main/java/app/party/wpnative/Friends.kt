package app.party.wpnative

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent friend list.
 *
 * Purana build sirf display names rakhta tha. Woh list jaisi thi waisi hi rehti hai;
 * naya code map har friend ke stable WP1 code ko us naam se jorta hai. Is tarah old
 * chats migrate kiye baghair chalti rehti hain aur nayi friendships code-backed hoti hain.
 */
object Friends {
    private const val PREF = "wp_friends"
    private const val KEY = "names"
    private const val KEY_CODES = "codes_v1"
    private const val SEP = "\u001f"

    data class Entry(val name: String, val code: String = "")

    fun all(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(SEP).map { it.trim() }
            .filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun entries(ctx: Context): List<Entry> {
        val codes = readCodes(ctx)
        return all(ctx).map { name ->
            val code = codes.keys().asSequence().firstOrNull {
                codes.optString(it).equals(name, ignoreCase = true)
            }.orEmpty()
            Entry(name, code)
        }
    }

    fun add(ctx: Context, name: String, code: String = "") {
        val n = name.trim().take(40)
        if (n.isEmpty()) return
        val normalized = WpUser.normalizeFriendCode(code)
        val names = all(ctx).toMutableList()
        val codes = readCodes(ctx)

        if (normalized.length == 8) {
            // Isi code ka purana placeholder/name ho to ek hi row rakho.
            val oldName = codes.optString(normalized)
            if (oldName.isNotBlank() && !oldName.equals(n, ignoreCase = true)) {
                names.removeAll { it.equals(oldName, ignoreCase = true) }
            }
            // Ek display name ke saath purana code ho to stale mapping hatao.
            val stale = codes.keys().asSequence().filter {
                codes.optString(it).equals(n, ignoreCase = true) && it != normalized
            }.toList()
            stale.forEach { codes.remove(it) }
            codes.put(normalized, n)
        }

        if (names.none { it.equals(n, ignoreCase = true) }) names.add(n)
        save(ctx, names, codes)
    }

    fun remove(ctx: Context, name: String) {
        val names = all(ctx).filterNot { it.equals(name, ignoreCase = true) }
        val codes = readCodes(ctx)
        val remove = codes.keys().asSequence().filter {
            codes.optString(it).equals(name, ignoreCase = true)
        }.toList()
        val friendCode = remove.firstOrNull().orEmpty()
        remove.forEach { codes.remove(it) }
        save(ctx, names, codes)

        // Purani name-based aur nayi code-based dono local caches hatao; remote history
        // remove-friend par nahi mit-ti, bilkul pehle wale DM behavior ki tarah.
        try {
            val me = WpUser.me(ctx)
            val legacyId = WpUser.chatId(me, name)
            val stableId = WpUser.friendChatId(ctx, name, friendCode)
            ctx.getSharedPreferences("wp_chat_cache", Context.MODE_PRIVATE).edit()
                .remove(legacyId).remove(stableId).apply()
        } catch (_: Throwable) { }
    }

    fun has(ctx: Context, name: String): Boolean =
        all(ctx).any { it.equals(name, ignoreCase = true) }

    fun hasCode(ctx: Context, code: String): Boolean {
        val raw = WpUser.normalizeFriendCode(code)
        return raw.length == 8 && readCodes(ctx).has(raw)
    }

    fun codeForName(ctx: Context, name: String): String {
        val codes = readCodes(ctx)
        return codes.keys().asSequence().firstOrNull {
            codes.optString(it).equals(name, ignoreCase = true)
        }.orEmpty()
    }

    fun nameForCode(ctx: Context, code: String): String? {
        val raw = WpUser.normalizeFriendCode(code)
        return readCodes(ctx).optString(raw).takeIf { it.isNotBlank() }
    }

    private fun readCodes(ctx: Context): JSONObject {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_CODES, "{}") ?: "{}"
        return try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
    }

    private fun save(ctx: Context, names: List<String>, codes: JSONObject) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, names.joinToString(SEP))
            .putString(KEY_CODES, codes.toString())
            .apply()
    }
}
