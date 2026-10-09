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
    private const val DEMO_CLEANED = "demo_friends_cleaned_v1"
    private const val SEP = "\u001f"   // naam mein kabhi nahi aane wala separator

    /** Purane test build ke nakli dost — ab production list mein kabhi nahi aayenge. */
    private val DEMO_NAMES = setOf("Dost 1", "Dost 2", "Dost 3", "Dost 4")

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * Fresh install = khaali friend list.
     *
     * Purana APK update ho to us mein save huay Dost 1..4 bhi ek baar khud saaf:
     * list, phone chat cache, photo/voice cache, DP aur presence — asli add kiye hue
     * doston ko bilkul nahi chhedta.
     */
    private fun prepare(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(SEEDED, false)) {
            p.edit().putBoolean(SEEDED, true).putString(KEY, "").apply()
        }
        if (p.getBoolean(DEMO_CLEANED, false)) return

        val raw = p.getString(KEY, "") ?: ""
        val clean = raw.split(SEP).filter { it.isNotBlank() && it !in DEMO_NAMES }
        p.edit()
            .putString(KEY, clean.joinToString(SEP))
            .putBoolean(DEMO_CLEANED, true)
            .apply()

        val me = WpUser.me(ctx)
        DEMO_NAMES.forEach { name ->
            val chatId = WpUser.chatId(me, name)
            ChatCache.clear(ctx, chatId)
            MediaCleanup.clearChat(ctx, chatId)
            Presence.forget(ctx, name)
            DpStore.forget(ctx, name)
        }
    }

    fun all(ctx: Context): MutableList<String> {
        prepare(ctx)
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
