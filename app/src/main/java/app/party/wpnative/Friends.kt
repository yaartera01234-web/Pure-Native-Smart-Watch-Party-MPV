package app.party.wpnative

import android.content.Context

/**
 * Doston ki list — EK hi jagah (SharedPreferences), taake Inbox / Chat / Calls
 * sab screens ko pata rahe ke kaun friend hai aur kaun remove ho chuka hai.
 *
 * Website (party-final1.html) mein ye kaam `S.friends` + `dmSetFriendState()` karta hai
 * (remove karte hi friend list se nikal jata hai). Yahan wahi behaviour, phone me save.
 */
object Friends {

    private const val PREF = "wp_friends"
    private const val KEY = "names"
    private const val SEEDED = "seeded"
    private const val SEP = ""   // naam mein kabhi nahi aane wala separator

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Pehli baar app chalane par demo dost daal dete hain (ek hi baar). */
    private fun seed(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(SEEDED, false)) return
        p.edit()
            .putBoolean(SEEDED, true)
            .putString(KEY, listOf("Dost 1", "Dost 2", "Dost 3", "Dost 4").joinToString(SEP))
            .apply()
    }

    fun all(ctx: Context): MutableList<String> {
        seed(ctx)
        val raw = prefs(ctx).getString(KEY, "") ?: ""
        return raw.split(SEP).filter { it.isNotBlank() }.toMutableList()
    }

    fun has(ctx: Context, name: String): Boolean = all(ctx).contains(name)

    fun remove(ctx: Context, name: String): Boolean {
        val list = all(ctx)
        if (!list.remove(name)) return false
        prefs(ctx).edit().putString(KEY, list.joinToString(SEP)).apply()
        return true
    }

    fun add(ctx: Context, name: String) {
        val list = all(ctx)
        if (list.contains(name)) return
        list.add(name)
        prefs(ctx).edit().putString(KEY, list.joinToString(SEP)).apply()
    }
}
