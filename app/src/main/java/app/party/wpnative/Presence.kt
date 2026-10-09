package app.party.wpnative

import android.content.Context

/**
 * Online / Offline + "kab aakhri baar aaya" — website ke `dmLivePresence` / `dmStatusLabel`
 * jaisa, bas itna farq ke user ne Offline ke saath waqt bhi mangwaaya hai
 * ("Offline • 12 min ago"), jo website mein nahi tha.
 *
 * State Firebase ke live presence signal se aati hai; koi nakli/test online value nahi.
 */
object Presence {

    private const val PREF = "wp_presence"
    private const val CLEANED = "demo_presence_cleaned_v1"
    private const val K_ON = "on_"      // + naam  -> Boolean
    private const val K_SEEN = "seen_"  // + naam  -> Long (millis)
    private val DEMO_NAMES = listOf("Dost 1", "Dost 2", "Dost 3", "Dost 4")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Update par purane test presence keys bhi ek hi baar saaf. */
    private fun prepare(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(CLEANED, false)) return
        val e = p.edit().putBoolean(CLEANED, true).remove("seeded")
        DEMO_NAMES.forEach { name -> e.remove(K_ON + name).remove(K_SEEN + name) }
        e.apply()
    }

    /** Ek dost ki phone wali presence mita do. */
    fun forget(ctx: Context, name: String) {
        prepare(ctx)
        prefs(ctx).edit().remove(K_ON + name).remove(K_SEEN + name).apply()
    }

    fun isOnline(ctx: Context, name: String): Boolean {
        prepare(ctx)
        return prefs(ctx).getBoolean(K_ON + name, false)
    }

    /** Aakhri baar kab online tha (millis). 0 = pata nahi. */
    fun seenAt(ctx: Context, name: String): Long {
        prepare(ctx)
        return prefs(ctx).getLong(K_SEEN + name, 0L)
    }

    /** Online/Offline badlo — sath mein "last seen" bhi update hota hai. */
    fun setOnline(ctx: Context, name: String, online: Boolean) {
        prepare(ctx)
        prefs(ctx).edit()
            .putBoolean(K_ON + name, online)
            .putLong(K_SEEN + name, System.currentTimeMillis())
            .apply()
    }

    /** Firebase ke presence signal se state set karo (online + last seen dono). */
    fun setState(ctx: Context, name: String, online: Boolean, seenAt: Long) {
        prepare(ctx)
        prefs(ctx).edit()
            .putBoolean(K_ON + name, online)
            .putLong(K_SEEN + name, if (seenAt > 0L) seenAt else System.currentTimeMillis())
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
