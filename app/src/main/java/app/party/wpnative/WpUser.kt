package app.party.wpnative

import android.content.Context

/**
 * Apni pehchaan — abhi simple naam (phone mein save).
 * Asli auth (phone number / Google) aane par yahi jagah user id de degi,
 * baqi code nahi badlega.
 *
 * Chat id dono taraf same nikalne ka tariqa: dono naam sort kar ke jodo,
 * to "Ali" <-> "Sara" aur "Sara" <-> "Ali" dono ka ek hi chat banta hai.
 */
object WpUser {

    private const val PREF = "wp_user"
    private const val K_NAME = "name"

    fun me(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val n = p.getString(K_NAME, null)
        if (!n.isNullOrBlank()) return n
        p.edit().putString(K_NAME, "Me").apply()
        return "Me"
    }

    /** Jo naam user ne khud likha hai — "" agar abhi default ("Me") hai. */
    fun savedName(ctx: Context): String {
        val n = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_NAME, null)
        return if (n.isNullOrBlank() || n == "Me") "" else n
    }

    fun setName(ctx: Context, name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(K_NAME, n).apply()
    }

    /** Dono taraf ek hi id: ["Ali","Sara"].sorted() -> "Ali|Sara". */
    fun chatId(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")
}
