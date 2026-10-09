package app.party.wpnative

import android.content.Context

/**
 * Apni pehchaan — abhi simple naam (phone mein save).
 * Asli auth (phone number / Google) aane par yahi jagah user id de degi,
 * baqi code nahi badlega.
 *
 * Chat id dono taraf same nikalne ka tariqa: dono naam sort kar ke jodo,
 * to "Me" <-> "Dost 1" aur "Dost 1" <-> "Me" dono ka ek hi chat banta hai.
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

    fun setName(ctx: Context, name: String) {
        val n = name.trim()
        if (n.isBlank()) return
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(K_NAME, n).apply()
    }

    /** Dono taraf ek hi id: ["Dost 1","Me"].sorted() -> "Dost 1|Me". */
    fun chatId(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")
}
