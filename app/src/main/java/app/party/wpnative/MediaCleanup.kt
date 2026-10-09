package app.party.wpnative

import android.content.Context

/**
 * **3 din baad photo/voice khud hi mit jayen** — Firestore se bhi, phone se bhi.
 *
 * Kyun: Firestore (Spark) mein 1 GB ki jagah **jamaa** hoti hai (mahine par
 * saaf nahi hoti). Photo/voice ka base64 wahin rehta hai, is liye 1 GB bharne
 * ka dar tha. Ab har photo/voice 3 din baad apne aap hat jati hai:
 *
 *   1. Firestore ke message se `media` (base64) hata di jati hai  -> jagah khali
 *   2. Phone ki cached copy bhi mit jati hai                     -> phone saaf
 *   3. Purani/Adhuri (L<waqt>) files bhi 3 din baad hat jati hain
 *
 * Kaam **app khulte hi** hota hai (koi server nahi, koi Blaze nahi, koi
 * Cloud Function nahi — sab phone ke andar).
 *
 * Registry = chhoti si list: "chatId|msgId|waqt" — isi se pata chalta hai ke
 * Firestore mein kaunsi media abhi maujood hai. (Koi query nahi, is liye
 * Firestore ke 50,000 reads/day mein se ek bhi kharch nahi hota.)
 */
object MediaCleanup {

    /** Kitne din baad media mit jaye. */
    const val DAYS = 3
    private val KEEP_MS = DAYS * 24L * 60L * 60L * 1000L

    private const val PREF = "media_idx"
    private const val KEY = "items"
    private const val MAX_ITEMS = 5000         // 3 din ka bohat bada buffer; entry na kho jaye
    private const val PER_RUN = 80             // ek baar mein itni media saaf

    /** Chhote se waqt mein do baar na chale (6 ghante). */
    private const val GAP_MS = 6L * 60L * 60L * 1000L
    private var lastRun = 0L

    /** Ye message ke paas asli media hai — 3 din baad isay hatana hai. */
    fun note(ctx: Context, chatId: String, msgId: String, ts: Long) {
        if (chatId.isBlank() || msgId.isBlank() || ts <= 0L) return
        try {
            val items = load(ctx).toMutableList()
            val line = "$chatId|$msgId|$ts"
            if (!items.contains(line)) {
                items.add(line)
                if (items.size > MAX_ITEMS) items.subList(0, items.size - MAX_ITEMS).clear()
                save(ctx, items)
            }
        } catch (t: Throwable) { }
    }

    /**
     * Message 120 ki had se bahar gaya -> uski media aur registry entry bhi abhi hatao.
     */
    fun forget(ctx: Context, chatId: String, msgId: String) {
        if (chatId.isBlank() || msgId.isBlank()) return
        try {
            val prefix = "$chatId|$msgId|"
            val items = load(ctx).toMutableList()
            if (items.removeAll { it.startsWith(prefix) }) save(ctx, items)
            MediaCache.delete(ctx, msgId)
        } catch (t: Throwable) { }
    }

    /**
     * Firestore ne jo naye 120 diye, is chat ki baqi registry/files purani hain.
     * `preserveAfter` ke baad bheja gaya pending message query ke darmiyan na mite.
     */
    fun keepOnly(ctx: Context, chatId: String, keep: Set<String>, preserveAfter: Long) {
        try {
            val items = load(ctx).toMutableList()
            val gone = ArrayList<String>()
            items.removeAll { raw ->
                val p = raw.split("|")
                val sameChat = p.getOrNull(0) == chatId
                val id = p.getOrNull(1) ?: ""
                val ts = p.getOrNull(2)?.toLongOrNull() ?: 0L
                val remove = sameChat && id !in keep && ts <= preserveAfter
                if (remove && id.isNotBlank()) gone.add(id)
                remove
            }
            gone.forEach { MediaCache.delete(ctx, it) }
            save(ctx, items)
        } catch (t: Throwable) { }
    }

    /** App khulte hi (ya chat khulte hi) — 6 ghante mein ek baar se zyada nahi. */
    fun runIfDue(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRun < GAP_MS) return
        lastRun = now
        run(ctx)
    }

    /** Poora saafa: pehle Firestore se media hatayen, phir phone ki files. */
    fun run(ctx: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val items = load(ctx).toMutableList()
        sweepLocal(ctx, items)                  // phone ki purani/awara files
        if (!FirebaseChat.isReady(ctx)) return
        step(ctx, items, cutoff, 0, 0)
    }

    /** Ek ek kar ke (zor na pade) — har media ke liye Firestore se `media` hatao. */
    private fun step(ctx: Context, items: MutableList<String>, cutoff: Long, i: Int, tried: Int) {
        if (i >= items.size || tried >= PER_RUN) { save(ctx, items); return }
        val raw = items[i]
        val p = raw.split("|")
        val ts = p.getOrNull(2)?.toLongOrNull() ?: 0L
        if (p.size < 3) { items.removeAt(i); step(ctx, items, cutoff, i, tried); return }
        if (ts >= cutoff) { step(ctx, items, cutoff, i + 1, tried); return }   // abhi 3 din nahi hue
        FirebaseChat.dropMedia(ctx, p[0], p[1]) { ok ->
            if (ok) {
                items.remove(raw)
                MediaCache.delete(ctx, p[1])    // Firestore se gai -> phone se bhi
            }
            // na mili to agli baar phir koshish (entry rahegi)
            step(ctx, items, cutoff, if (ok) i else i + 1, tried + 1)
        }
    }

    /**
     * Phone ki woh cached media hat jaye jo **ab kisi kaam ki nahi**:
     *  - 3 din purani file jiski entry registry mein nahi (yaani ab kahin nahi)
     *  - adhuri bheji hui (L<waqt>) files bhi isi mein aati hain
     */
    private fun sweepLocal(ctx: Context, items: List<String>) {
        try {
            val live = HashSet<String>()
            items.forEach { it.split("|").getOrNull(1)?.let { id -> live.add(id) } }
            MediaCache.purgeOlderThan(ctx, System.currentTimeMillis() - KEEP_MS, live)
        } catch (t: Throwable) { }
    }

    private fun load(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    private fun save(ctx: Context, items: List<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, items.joinToString("\n"))
            .apply()
    }
}
