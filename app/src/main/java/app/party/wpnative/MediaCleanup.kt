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
 * Registry ek chhoti local list hai: chatId + msgId + waqt. Koi Firestore
 * query nahi, is liye 50,000 reads/day mein se ek bhi kharch nahi hota.
 */
object MediaCleanup {

    /** Kitne din baad media mit jaye. */
    const val DAYS = 3
    private val KEEP_MS = DAYS * 24L * 60L * 60L * 1000L

    private const val PREF = "media_idx"
    private const val KEY = "items"
    /** Chat id ke andar `|` hota hai, is liye registry ka separator ye anokha harf hai. */
    private const val SEP = "\u001e"
    private const val MAX_ITEMS = 5000         // 3 din ka bohat bada buffer; entry na kho jaye
    private const val PER_RUN = 80             // ek baar mein itni media saaf

    /** Chhote se waqt mein do baar na chale (6 ghante). */
    private const val GAP_MS = 6L * 60L * 60L * 1000L
    private var lastRun = 0L

    private data class Ref(val chatId: String, val msgId: String, val ts: Long)

    /** Ye message ke paas asli media hai — 3 din baad isay hatana hai. */
    fun note(ctx: Context, chatId: String, msgId: String, ts: Long) {
        if (chatId.isBlank() || msgId.isBlank() || ts <= 0L) return
        try {
            val items = load(ctx).toMutableList()
            val exists = items.any { raw ->
                parse(raw)?.let { it.chatId == chatId && it.msgId == msgId } == true
            }
            if (!exists) {
                items.add(pack(Ref(chatId, msgId, ts)))
                if (items.size > MAX_ITEMS) items.subList(0, items.size - MAX_ITEMS).clear()
                save(ctx, items)
            }
        } catch (t: Throwable) { }
    }

    /** Purani test chat ki saari local photo/voice + registry entries saaf. */
    fun clearChat(ctx: Context, chatId: String) {
        if (chatId.isBlank()) return
        try {
            val items = load(ctx).toMutableList()
            val gone = ArrayList<String>()
            items.removeAll { raw ->
                val ref = parse(raw) ?: return@removeAll true
                val remove = ref.chatId == chatId
                if (remove) gone.add(ref.msgId)
                remove
            }
            gone.forEach { MediaCache.delete(ctx, it) }
            save(ctx, items)
        } catch (t: Throwable) { }
    }

    /** Message 120 ki had se bahar gaya -> media aur registry entry bhi abhi hatao. */
    fun forget(ctx: Context, chatId: String, msgId: String) {
        if (chatId.isBlank() || msgId.isBlank()) return
        try {
            val items = load(ctx).toMutableList()
            if (items.removeAll { raw ->
                    parse(raw)?.let { it.chatId == chatId && it.msgId == msgId } == true
                }) save(ctx, items)
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
                val ref = parse(raw) ?: return@removeAll true
                val remove = ref.chatId == chatId && ref.msgId !in keep && ref.ts <= preserveAfter
                if (remove) gone.add(ref.msgId)
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
        val ref = parse(raw)
        if (ref == null) { items.removeAt(i); step(ctx, items, cutoff, i, tried); return }
        if (ref.ts >= cutoff) { step(ctx, items, cutoff, i + 1, tried); return } // abhi 3 din nahi hue
        FirebaseChat.dropMedia(ctx, ref.chatId, ref.msgId) { ok ->
            if (ok) {
                items.remove(raw)
                MediaCache.delete(ctx, ref.msgId)    // Firestore se gai -> phone se bhi
            }
            // na mili to agli baar phir koshish (entry rahegi)
            step(ctx, items, cutoff, if (ok) i else i + 1, tried + 1)
        }
    }

    /** Phone ki 3 din purani, registry ke baghair padi media bhi saaf. */
    private fun sweepLocal(ctx: Context, items: List<String>) {
        try {
            val live = HashSet<String>()
            items.forEach { raw -> parse(raw)?.let { live.add(it.msgId) } }
            MediaCache.purgeOlderThan(ctx, System.currentTimeMillis() - KEEP_MS, live)
        } catch (t: Throwable) { }
    }

    private fun pack(ref: Ref): String = "${ref.chatId}$SEP${ref.msgId}$SEP${ref.ts}"

    /**
     * Naya format SEP use karta hai. Pichhle APK ka `chatId|msgId|ts` format bhi
     * daayen se padh lete hain, kyun ke chatId khud `naam|naam` hota hai.
     */
    private fun parse(raw: String): Ref? {
        try {
            if (raw.contains(SEP)) {
                val p = raw.split(SEP)
                if (p.size == 3) {
                    val ts = p[2].toLongOrNull() ?: return null
                    if (p[0].isNotBlank() && p[1].isNotBlank()) return Ref(p[0], p[1], ts)
                }
                return null
            }
            // Legacy migration: aakhri do `|` hi msgId aur waqt ko alag karte hain.
            val last = raw.lastIndexOf('|')
            if (last <= 0 || last >= raw.lastIndex) return null
            val prev = raw.lastIndexOf('|', last - 1)
            if (prev <= 0 || prev >= last - 1) return null
            val ts = raw.substring(last + 1).toLongOrNull() ?: return null
            return Ref(raw.substring(0, prev), raw.substring(prev + 1, last), ts)
        } catch (t: Throwable) { return null }
    }

    private fun load(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    /** Save karte waqt purana format bhi naya bana jata hai aur duplicate nikal jata hai. */
    private fun save(ctx: Context, items: List<String>) {
        val unique = LinkedHashMap<String, Ref>()
        items.forEach { raw ->
            parse(raw)?.let { ref -> unique[ref.chatId + SEP + ref.msgId] = ref }
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, unique.values.joinToString("\n") { pack(it) })
            .apply()
    }
}
