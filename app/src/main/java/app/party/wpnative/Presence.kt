package app.party.wpnative

import android.content.Context

/**
 * Online / Offline + "kab aakhri baar aaya" — website ke `dmLivePresence` / `dmStatusLabel`
 * jaisa, bas itna farq ke user ne Offline ke saath waqt bhi mangwaaya hai
 * ("Offline • 12 min ago"), jo website mein nahi tha.
 *
 * Abhi demo values hain (Dost 1 = 12 min pehle, Dost 3 = 1 ghanta, Dost 4 = 1 din).
 * Asli E2E aane par server/broker ke presence signal se `setOnline()` chalega.
 */
object Presence {

    private const val PREF = "wp_presence"
    private const val SEED = "seeded"
    private const val K_ON = "on_"      // + naam  -> Boolean
    private const val K_SEEN = "seen_"  // + naam  -> Long (millis)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Pehli baar chalane par demo presence (ek hi baar). */
    private fun seed(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(SEED, false)) return
        val now = System.currentTimeMillis()
        p.edit().putBoolean(SEED, true)
            .putBoolean(K_ON + "Dost 1", false).putLong(K_SEEN + "Dost 1", now - 12 * 60_000L)
            .putBoolean(K_ON + "Dost 2", true).putLong(K_SEEN + "Dost 2", now)
            .putBoolean(K_ON + "Dost 3", false).putLong(K_SEEN + "Dost 3", now - 65 * 60_000L)
            .putBoolean(K_ON + "Dost 4", false).putLong(K_SEEN + "Dost 4", now - 26 * 60 * 60_000L)
            .apply()
    }

    fun isOnline(ctx: Context, name: String): Boolean {
        seed(ctx)
        return prefs(ctx).getBoolean(K_ON + name, false)
    }

    /** Aakhri baar kab online tha (millis). 0 = pata nahi. */
    fun seenAt(ctx: Context, name: String): Long {
        seed(ctx)
        return prefs(ctx).getLong(K_SEEN + name, 0L)
    }

    /** Online/Offline badlo — sath mein "last seen" bhi update hota hai. */
    fun setOnline(ctx: Context, name: String, online: Boolean) {
        seed(ctx)
        prefs(ctx).edit()
            .putBoolean(K_ON + name, online)
            .putLong(K_SEEN + name, System.currentTimeMillis())
            .apply()
    }

    /** Header ka text: "Online" ya "Offline • 12 min ago". */
    fun label(ctx: Context, name: String): String {
        if (isOnline(ctx, name)) return "Online"
        val ts = seenAt(ctx, name)
        return if (ts <= 0L) "Offline" else "Offline • " + ago(ts)
    }

    /** "abhi" / "12 min ago" / "1 hour ago" / "1 day ago" ... */
    fun ago(ts: Long): String {
        if (ts <= 0L) return ""
        val s = ((System.currentTimeMillis() - ts) / 1000L).coerceAtLeast(0L)
        if (s < 60) return "abhi"
        val m = s / 60
        if (m < 60) return "$m min ago"
        val h = m / 60
        if (h < 24) return if (h == 1L) "1 hour ago" else "$h hours ago"
        val d = h / 24
        if (d < 7) return if (d == 1L) "1 day ago" else "$d days ago"
        val w = d / 7
        if (w < 5) return if (w == 1L) "1 week ago" else "$w weeks ago"
        val mo = d / 30
        return if (mo <= 1L) "1 month ago" else "$mo months ago"
    }
}
